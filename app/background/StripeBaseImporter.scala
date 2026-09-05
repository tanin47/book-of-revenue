package background

import database.models.{StripeImporterJob, StripeImporterJobCursor}
import database.services.{RawStripeObjectService, StripeImporterJobCursorService, StripeImporterJobService}
import framework.Helpers.await
import org.jobrunr.jobs.lambdas.{JobRequest, JobRequestHandler}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, Json}
import play.api.{Environment, Logger, Mode, Play}
import services.StripeService
import services.StripeService.ListResult

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

trait StripeBaseImporter {
  def rawStripeObjectService: RawStripeObjectService
  def stripeImporterJobCursorService: StripeImporterJobCursorService

  private[this] val logger = Logger(getClass)

  def importAllWithWrite(
    job: StripeImporterJob,
    objectType: String,
    customerId: Option[String] = None
  )(
    fetch: (Option[String], Option[String]) => ListResult[JsObject],
    write: Seq[JsObject] => Unit
  ): Unit = {
    logger.info(s"Importing $objectType with customer=$customerId.")

    var count = 0
    var done = false
    while (!done) {
      val cursor = await(stripeImporterJobCursorService.get(job.id, objectType, customerId)) match {
        case Some(cursor) => cursor
        case None => await(stripeImporterJobCursorService.create(StripeImporterJobCursorService.CreateData(job.id, objectType, customerId)))
      }
      val result = fetch(cursor.startingAfter, cursor.endingBefore)
      count += result.items.size
      logger.debug(s"Got ${result.items.size} objects. Example: ${result.items.headOption.map { i => (i \ "id").as[String] }}")

      write(result.items)

      val latestId = cursor.latestId.orElse(result.items.headOption.map { i => (i \ "id").as[String] })
      await(stripeImporterJobCursorService.update(
        jobId = job.id,
        objectType = objectType,
        customerId = customerId,
        latestId = latestId,
        startingAfter = if (result.hasMore && cursor.mode == StripeImporterJobCursor.Mode.StartingAfter) {
          result.items.lastOption.map { i => (i \ "id").as[String] }
        } else {
          None
        },
        endingBefore = if (cursor.mode == StripeImporterJobCursor.Mode.EndingBefore) {
          result.items.headOption.map { i => (i \ "id").as[String] }.orElse(cursor.endingBefore)
        } else if (!result.hasMore && cursor.mode == StripeImporterJobCursor.Mode.StartingAfter) {
          latestId
        } else {
          None
        }
      ))

      done = !result.hasMore
    }
    logger.info(s"Imported $count objects of $objectType with customer=$customerId.")
  }

  def importAll(
    job: StripeImporterJob,
    objectType: String,
    customerId: Option[String] = None
  )(
    fn: (Option[String], Option[String]) => ListResult[JsObject]
  ): Unit = {
    importAllWithWrite(
      job = job,
      objectType = objectType,
      customerId = customerId
    )(
      fetch = fn,
      write = { items =>
        await(rawStripeObjectService.create(items.map { item =>
          RawStripeObjectService.CreateData(
            id = (item \ "id").as[String],
            stripeAccountId = job.stripeAccountId,
            liveMode = job.liveMode,
            objectType = (item \ "object").as[String],
            rawJson = item.toString
          )
        }))
      }
    )
  }
}

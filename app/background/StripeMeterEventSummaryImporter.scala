package background

import database.services.{PriceService, RawStripeObjectService, StripeAccountService, SubscriptionItemService, SubscriptionService, TrackedExceptionService}
import framework.Helpers.await
import framework.Instant
import org.jobrunr.jobs.lambdas.{JobRequest, JobRequestHandler}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, JsString}
import play.api.{Environment, Logger, Mode, Play}
import services.StripeService
import services.StripeService.ListResult

import java.time.temporal.ChronoUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

case class StripeMeterEventSummaryImporterRequest() extends JobRequest {
  def getJobRequestHandler(): Class[StripeMeterEventSummaryImporter] = classOf[StripeMeterEventSummaryImporter]
}


object StripeMeterEventSummaryImporter {
  def main(args: Array[String]): Unit = {
    val app = GuiceApplicationBuilder(Environment.simple(mode = Mode.Dev)).build()

    Play.start(app)
    val handler = app.injector.instanceOf[StripeMeterEventSummaryImporter]
    handler.run(StripeMeterEventSummaryImporterRequest())
  }
}

@Singleton
class StripeMeterEventSummaryImporter @Inject() (
  stripeService: StripeService,
  stripeAccountService: StripeAccountService,
  rawStripeObjectService: RawStripeObjectService,
  subscriptionService: SubscriptionService,
  subscriptionItemService: SubscriptionItemService,
  priceService: PriceService,
  trackedExceptionService: TrackedExceptionService,
)(implicit ec: ExecutionContext) extends BaseJobRequestHandler[StripeMeterEventSummaryImporterRequest](trackedExceptionService) {
  private[this] val logger = Logger(getClass)

  // Event summaries require an aligned time window; we floor/ceil to the hour to satisfy Stripe.
  private[this] val oneHour = 3600L

  def run2(req: StripeMeterEventSummaryImporterRequest): Unit = {
    val accounts = await(stripeAccountService.getAll())

    accounts.foreach { account =>
      account.liveModeApiKey.foreach { apiKey => importData(account.id, apiKey, liveMode = true) }
      account.testModeApiKey.foreach { apiKey => importData(account.id, apiKey, liveMode = false) }
    }
  }

  private[this] def importData(stripeAccountId: String, apiKey: String, liveMode: Boolean): Unit = {
    logger.info(s"Importing meter event summaries for stripeAccountId=$stripeAccountId liveMode=$liveMode")
    val subscriptionsById = await(subscriptionService.getAll(stripeAccountId, liveMode)).map { s => s.id -> s }.toMap
    val pricesById = await(priceService.getAll(stripeAccountId, liveMode)).map { p => p.id -> p }.toMap
    val subscriptionItems = await(subscriptionItemService.getAll(stripeAccountId, liveMode))

    subscriptionItems.foreach { item =>
      val meterIdOpt = pricesById.get(item.priceId).flatMap(_.recurringMeterId)
      val subscriptionOpt = subscriptionsById.get(item.subscriptionId)

      (meterIdOpt, subscriptionOpt) match {
        case (Some(meterId), Some(subscription)) =>
          val startTime = floorToHour(subscription.startDate.getEpochSecond)
          val endTime = ceilToHour(item.currentPeriodEnd.getEpochSecond)

          logger.info(s"Importing meter event summaries for meter=$meterId customer=${subscription.customerId} window=[$startTime, $endTime]")

          importAll(stripeAccountId, liveMode) { startingAfter =>
            val result = await(stripeService.listMeterEventSummaries(apiKey,
              meterId = meterId,
              customerId = subscription.customerId,
              startTime = startTime,
              endTime = endTime,
              startingAfter = startingAfter
            ))

            // The meter id and customer id are not part of the summary payload, so we inject them from the
            // subscription context. This way the stored raw object is self-contained for normalization.
            result.copy(
              items = result.items.map { summary =>
                summary +
                  ("meter" -> JsString(meterId)) +
                  ("customer" -> JsString(subscription.customerId)) +
                  ("id" -> JsString(s"${meterId}__${(summary \ "id").as[String]}"))
              }
            )
          }
        case _ =>
          logger.info(s"Skipping subscription item ${item.id}: meter or subscription is missing.")
      }
    }
  }

  private[this] def floorToHour(epochSecond: Long): Long = (epochSecond / oneHour) * oneHour

  private[this] def ceilToHour(epochSecond: Long): Long = ((epochSecond + oneHour - 1) / oneHour) * oneHour

  private def importAll(stripeAccountId: String, liveMode: Boolean)(fn: Option[String] => ListResult[JsObject]): Unit = {
    var startingAfter: Option[String] = None
    var done = false

    while (!done) {
      val result = fn(startingAfter)
      logger.info(s"Got ${result.items.size} meter event summaries. Example: ${result.items.headOption.map { i => (i \ "id").as[String] }}")

      startingAfter = result.items.lastOption.map { i => (i \ "id").as[String] }

      val _ = await(rawStripeObjectService.create(result.items.map { item =>
        RawStripeObjectService.CreateData(
          id = (item \ "id").as[String],
          stripeAccountId = stripeAccountId,
          liveMode = liveMode,
          objectType = (item \ "object").as[String],
          rawJson = item.toString
        )
      }))

      val tooOld = result.items.lastOption.exists { i => Instant.ofEpochSecond((i \ "start_time").as[Long]).isBefore(Instant.now().minus(36, ChronoUnit.DAYS))}
      done = !result.hasMore || startingAfter.isEmpty || tooOld
    }
  }
}

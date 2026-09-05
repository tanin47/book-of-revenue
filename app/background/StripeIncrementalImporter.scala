package background

import database.models.StripeImporterJob
import database.services.*
import framework.Helpers.await
import org.jobrunr.jobs.lambdas.JobRequest
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, Json}
import play.api.{Environment, Logger, Mode, Play}
import services.StripeService

import java.util.concurrent.Executors
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

case class StripeIncrementalImporterRequest() extends JobRequest {
  def getJobRequestHandler(): Class[StripeIncrementalImporter] = classOf[StripeIncrementalImporter]
}


object StripeIncrementalImporter {
  def main(args: Array[String]): Unit = {
    val app = GuiceApplicationBuilder(Environment.simple(mode = Mode.Dev)).build()

    Play.start(app)
    val handler = app.injector.instanceOf[StripeIncrementalImporter]
    handler.run(StripeIncrementalImporterRequest())
  }
}

@Singleton
class StripeIncrementalImporter @Inject() (
  val stripeService: StripeService,
  val stripeAccountService: StripeAccountService,
  val rawStripeObjectService: RawStripeObjectService,
  val stripeImporterJobService: StripeImporterJobService,
  val stripeImporterJobCursorService: StripeImporterJobCursorService,
  trackedExceptionService: TrackedExceptionService,
)(implicit ec: ExecutionContext)
  extends BaseJobRequestHandler[StripeIncrementalImporterRequest](trackedExceptionService) with StripeBaseImporter {
  private[this] val logger = Logger(getClass)

  def run2(req: StripeIncrementalImporterRequest): Unit = {
    val accounts = await(stripeAccountService.getAll())

    accounts.foreach { account =>
      account.liveModeApiKey.foreach { apiKey => importData(account.id, apiKey, liveMode = true) }
      account.testModeApiKey.foreach { apiKey => importData(account.id, apiKey, liveMode = false) }
    }
  }

  def importData(accountId: String, apiKey: String, liveMode: Boolean): Unit = {
    val job = await(stripeImporterJobService.getActive(StripeImporterJob.JobType.Batch, accountId, liveMode)) match {
      case Some(job) => job
      case None => await(stripeImporterJobService.create(StripeImporterJob.JobType.Batch, accountId, liveMode))
    }

    val executors = Executors.newFixedThreadPool(2)
    try {
      implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(executors)

      val _ = await(Future.sequence(Seq(
        Future {
          importAll(job, "balance_transaction") { (startingAfter, endingBefore) => await(stripeService.listBalanceTransactions(apiKey, startingAfter, endingBefore)) }
        },
        //      Future(importCustomerOwnedObjects(job, apiKey))
      )))
    } finally {
      executors.shutdown()
    }
  }

  // This loops through every customer. It would take too many API calls.
//  private def importCustomerOwnedObjects(job: StripeImporterJob, apiKey: String): Unit = {
//    var maxCustomerId: Option[String] = None
//    var done = false
//    val executors = Executors.newFixedThreadPool(100)
//
//    try {
//      implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(executors)
//      executors.shutdown()
//
//      while (!done) {
//        val customers = await(rawStripeObjectService.getObjects(job.stripeAccountId, job.liveMode, "customer", maxCustomerId))
//        val _ = await(Future.sequence(customers.map { customer =>
//          Future {
//            val json = Json.parse(customer.rawJson).as[JsObject]
//            val customerId = (json \ "id").as[String]
//            importAll(job, "customer_balance_transaction", Some(customerId)) { (startingAfter, endingBefore) =>
//              await(stripeService.listCustomerBalanceTransactions(apiKey, customerId, startingAfter, endingBefore))
//            }
//          }
//        }))
//
//        maxCustomerId = customers.lastOption.map(_.id)
//        done = maxCustomerId.isEmpty
//      }
//    } finally {
//      executors.shutdown()
//    }
//  }
}

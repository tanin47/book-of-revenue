package background

import database.models.{StripeAccount, StripeImporterJob, StripeImporterJobCursor}
import database.services.{RawStripeObjectService, StripeAccountService, StripeImporterJobCursorService, StripeImporterJobService, TrackedExceptionService}
import framework.Helpers.await
import framework.PlayConfig
import org.jobrunr.jobs.lambdas.{JobRequest, JobRequestHandler}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, Json}
import play.api.{Environment, Logger, Mode, Play}
import services.StripeService
import services.StripeService.ListResult

import java.util.concurrent.Executors
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

case class StripeImporterRequest() extends JobRequest {
  def getJobRequestHandler(): Class[StripeImporter] = classOf[StripeImporter]
}


object StripeImporter {
  def main(args: Array[String]): Unit = {
    val app = GuiceApplicationBuilder(Environment.simple(mode = Mode.Dev)).build()

    Play.start(app)
    val handler = app.injector.instanceOf[StripeImporter]
    handler.run(StripeImporterRequest())
  }
}

@Singleton
class StripeImporter @Inject() (
  val stripeService: StripeService,
  val stripeAccountService: StripeAccountService,
  val rawStripeObjectService: RawStripeObjectService,
  val stripeImporterJobService: StripeImporterJobService,
  val stripeImporterJobCursorService: StripeImporterJobCursorService,
  val config: PlayConfig,
  trackedExceptionService: TrackedExceptionService,
)(implicit ec: ExecutionContext)
  extends BaseJobRequestHandler[StripeImporterRequest](trackedExceptionService) with StripeBaseImporter {
  private[this] val logger = Logger(getClass)

  def run2(req: StripeImporterRequest): Unit = {
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
    val executors = Executors.newFixedThreadPool(10)

    try {
      implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(executors)

      val importCustomerFuture = Future.sequence(Seq(
          Future(importAll(job, "customer") { (startingAfter, endingBefore) => await(stripeService.listCustomers(apiKey, None, startingAfter, endingBefore)) }),
          Future {
            if (!liveMode) {
              importBillingClocks(job, apiKey)
            } else {
              ()
            }
          }
        ))
        .map { _ => importCustomerOwnedObjects(job, apiKey) }

      val _ = await(Future.sequence(Seq(
        importCustomerFuture,
        Future(importAll(job, "product") { (startingAfter, endingBefore) => await(stripeService.listProducts(apiKey, startingAfter, endingBefore)) }),
        Future(importAll(job, "price") { (startingAfter, endingBefore) => await(stripeService.listPrices(apiKey, startingAfter, endingBefore)) }),
        Future(importAll(job, "charge") { (startingAfter, endingBefore) => await(stripeService.listCharges(apiKey, None, startingAfter, endingBefore)) }),
        Future(importAll(job, "payment_intent") { (startingAfter, endingBefore) => await(stripeService.listPaymentIntents(apiKey, None, startingAfter, endingBefore)) }),
        Future(importAll(job, "balance_transaction") { (startingAfter, endingBefore) => await(stripeService.listBalanceTransactions(apiKey, startingAfter, endingBefore)) }),
        Future(importAll(job, "refund") { (startingAfter, endingBefore) => await(stripeService.listRefunds(apiKey, startingAfter, endingBefore)) }),
        Future(importAll(job, "dispute") { (startingAfter, endingBefore) => await(stripeService.listDisputes(apiKey, None, startingAfter, endingBefore)) }),
        Future(importAll(job, "invoice") { (startingAfter, endingBefore) => await(stripeService.listInvoices(apiKey, None, startingAfter, endingBefore)) }),
        Future(importAll(job, "coupon") { (startingAfter, endingBefore) => await(stripeService.listCoupons(apiKey, startingAfter, endingBefore)) }),
        Future(importAll(job, "credit_note") { (startingAfter, endingBefore) => await(stripeService.listCreditNotes(apiKey, None, startingAfter, endingBefore)) }),
        Future(importAll(job, "invoiceitem") { (startingAfter, endingBefore) => await(stripeService.listInvoiceItems(apiKey, None, startingAfter, endingBefore)) }),
        Future(importAll(job, "subscription") { (startingAfter, endingBefore) => await(stripeService.listSubscriptions(apiKey, None, startingAfter, endingBefore)) }),
        Future(importAll(job, "billing.credit_grant") { (startingAfter, endingBefore) => await(stripeService.listCreditGrants(apiKey, None, startingAfter, endingBefore)) }),
      )))
    } finally {
      executors.shutdown()
    }
  }

  private def importCustomerOwnedObjects(job: StripeImporterJob, apiKey: String): Unit = {
    val executors = Executors.newFixedThreadPool(100)

    try {
      implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(executors)
      var maxCustomerId: Option[String] = None
      var done = false

      while (!done) {
        val customers = await(rawStripeObjectService.getObjects(job.stripeAccountId, job.liveMode, "customer", maxCustomerId))
        val _ = await(Future.sequence(customers.map { customer =>
          Future {
            val json = Json.parse(customer.rawJson).as[JsObject]
            val customerId = (json \ "id").as[String]

            val fetchedCustomer = await(stripeService.getCustomer(apiKey, customerId))

            fetchedCustomer.filter { c => !(c \ "deleted").asOpt[Boolean].contains(true) }.foreach { fetchedCustomer =>
              importAll(job, "billing.credit_balance_transaction", Some(customerId)) { (startingAfter, endingBefore) =>
                await(stripeService.listCreditBalanceTransactions(apiKey, customerId, startingAfter, endingBefore))
              }
              importAll(job, "customer_balance_transaction", Some(customerId)) { (startingAfter, endingBefore) =>
                await(stripeService.listCustomerBalanceTransactions(apiKey, customerId, startingAfter, endingBefore))
              }

              val testClockId = (json \ "test_clock").asOpt[String]
              if (testClockId.isDefined) {
                // We have to import by customer because Billing Clock doesn't support listing all invoices.
                importAll(job, "invoice", Some(customerId)) { (startingAfter, endingBefore) =>
                  await(stripeService.listInvoices(apiKey, Some(customerId), startingAfter, endingBefore))
                }
                importAll(job, "credit_note", Some(customerId)) { (startingAfter, endingBefore) =>
                  await(stripeService.listCreditNotes(apiKey, Some(customerId), startingAfter, endingBefore))
                }
                importAll(job, "invoice_item", Some(customerId)) { (startingAfter, endingBefore) =>
                  await(stripeService.listInvoiceItems(apiKey, Some(customerId), startingAfter, endingBefore))
                }
                importAll(job, "subscription", Some(customerId)) { (startingAfter, endingBefore) =>
                  await(stripeService.listSubscriptions(apiKey, Some(customerId), startingAfter, endingBefore))
                }
                importAll(job, "billing.credit_grant", Some(customerId)) { (startingAfter, endingBefore) =>
                  await(stripeService.listCreditGrants(apiKey, Some(customerId), startingAfter, endingBefore))
                }
              }
            }
          }
        }))

        maxCustomerId = customers.lastOption.map(_.id)
        done = maxCustomerId.isEmpty
      }
    } finally {
      executors.shutdown()
    }
  }

  private[this] def importBillingClocks(job: StripeImporterJob, apiKey: String): Unit = {
    importAll(job, "test_helpers.test_clock") { (startingAfter, endingBefore) => await(stripeService.listTestClocks(apiKey, startingAfter, endingBefore)) }

    var maxId: Option[String] = None
    var done = false

    while (!done) {
      logger.info(s"Processing test clocks with max id $maxId.")
      val testClocks = await(rawStripeObjectService.getObjects(job.stripeAccountId, job.liveMode, "test_helpers.test_clock", maxId))
      maxId = testClocks.lastOption.map(_.id)
      testClocks.foreach { testClock =>
        val json = Json.parse(testClock.rawJson).as[JsObject]
        val testClockId = (json \ "id").as[String]
        val clock = await(stripeService.getTestClock(apiKey, testClockId))
        // Confirm that the clock still exists.
        clock.foreach { _ =>
          logger.info(s"Importing the customers for the test clock $testClockId.")
          importAll(job, s"clock-customer-$testClockId") { (startingAfter, endingBefore) =>
            await(stripeService.listCustomers(apiKey, Some(testClockId), startingAfter, endingBefore))
          }
        }
      }
      done = testClocks.isEmpty
    }
  }
}

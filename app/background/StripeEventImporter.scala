package background

import database.models.StripeImporterJob
import database.services.{RawStripeObjectService, StripeAccountService, StripeEventService, StripeImporterJobCursorService, StripeImporterJobService, TrackedExceptionService}
import framework.Helpers.await
import framework.Instant
import org.jobrunr.jobs.lambdas.{JobRequest, JobRequestHandler}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, Json}
import play.api.{Environment, Logger, Mode, Play}
import services.StripeService

import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

case class StripeEventImporterRequest() extends JobRequest {
  def getJobRequestHandler(): Class[StripeEventImporter] = classOf[StripeEventImporter]
}

object StripeEventImporter {
  def main(args: Array[String]): Unit = {
    val app = GuiceApplicationBuilder(Environment.simple(mode = Mode.Dev)).build()

    Play.start(app)
    val handler = app.injector.instanceOf[StripeEventImporter]
    handler.run(StripeEventImporterRequest())
  }
}

@Singleton
class StripeEventImporter @Inject() (
  val stripeService: StripeService,
  val stripeAccountService: StripeAccountService,
  val stripeEventService: StripeEventService,
  val rawStripeObjectService: RawStripeObjectService,
  val stripeImporterJobService: StripeImporterJobService,
  val stripeImporterJobCursorService: StripeImporterJobCursorService,
  trackedExceptionService: TrackedExceptionService,
)(implicit ec: ExecutionContext)
  extends BaseJobRequestHandler[StripeEventImporterRequest](trackedExceptionService) with StripeBaseImporter {
  import StripeEventImporter.*
  private[this] val logger = Logger(getClass)

  def run2(req: StripeEventImporterRequest): Unit = {
    val minCreatedAt = Instant.now().minus(3, ChronoUnit.HOURS)
    val accounts = await(stripeAccountService.getAll())

    accounts.foreach { account =>
      account.liveModeApiKey.foreach { apiKey => importEvents(minCreatedAt, account.id, apiKey, liveMode = true) }
      account.testModeApiKey.foreach { apiKey => importEvents(minCreatedAt, account.id, apiKey, liveMode = false) }
    }

    // Fetch and store the referenced objects for every unprocessed event, regardless of account;
    // each raw object is attributed using its own event's account and mode.
    updateRawStripeObjects(minCreatedAt)
  }

  private[this] def importEvents(minCreatedAt: Instant, accountId: String, apiKey: String, liveMode: Boolean): Unit = {
    val job = await(stripeImporterJobService.getActive(StripeImporterJob.JobType.Event, accountId, liveMode)) match {
      case Some(job) => job
      case None => await(stripeImporterJobService.create(StripeImporterJob.JobType.Event, accountId, liveMode))
    }
    importAllWithWrite(job, "event")(
      fetch = { (startingAfter, endingBefore) =>
        val result = await(stripeService.listEvents(apiKey, startingAfter, endingBefore))
        val filteredItems = result.items.filter { i => Instant.ofEpochSecond((i \ "created").as[Long]).isAfter(minCreatedAt) }
        result.copy(
          hasMore = filteredItems.nonEmpty,
          items = filteredItems
        )
      },
      write = { items =>
        await(stripeEventService.create(
          items
            .map { item =>
              StripeEventService.CreateData(
                id = (item \ "id").as[String],
                stripeAccountId = accountId,
                liveMode = liveMode,
                rawJson = item.toString,
                createdAt = Instant.ofEpochSecond((item \ "created").as[Long])
              )
            }
        ))
      }
    )
  }

  private[this] def updateRawStripeObjects(minCreatedAt: Instant): Unit = {
    // Map each account to the API key matching an event's mode, so we fetch referenced objects with the right key.
    val accountsById = await(stripeAccountService.getAll()).map { a => a.id -> a }.toMap

    var maxCreatedAt: Option[Instant] = None
    var maxId: Option[String] = None
    var done = false

    val executors = Executors.newFixedThreadPool(100)

    try {
      implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(executors)

      while (!done) {
        val events = await(stripeEventService.getUnprocessed(maxCreatedAt, maxId))

        val _ = await(Future.sequence(events.map { event =>
          Future {
            val json = Json.parse(event.rawJson).as[JsObject]
            val objectType = (json \ "data" \ "object" \ "object").as[String]
            val objectId = (json \ "data" \ "object" \ "id").asOpt[String]

            val apiKey = accountsById
              .get(event.stripeAccountId)
              .flatMap { account => if (event.liveMode) account.liveModeApiKey else account.testModeApiKey }

            apiKey.foreach { apiKey =>
              objectId.foreach { id =>
                val raw = await(objectType match {
                  case "billing.credit_balance_transaction" => stripeService.getCreditBalanceTransaction(apiKey, id)
                  case "billing.credit_grant" => stripeService.getCreditGrant(apiKey, id)
                  case "charge" => stripeService.getCharge(apiKey, id)
                  case "coupon" => stripeService.getCoupon(apiKey, id)
                  case "credit_note" => stripeService.getCreditNote(apiKey, id)
                  case "customer" => stripeService.getCustomer(apiKey, id)
                  case "dispute" => stripeService.getDispute(apiKey, id)
                  case "invoice" => stripeService.getInvoice(apiKey, id)
                  case "invoice_payment" => stripeService.getInvoicePayment(apiKey, id)
                  case "invoiceitem" => stripeService.getInvoiceItem(apiKey, id)
                  case "payment_intent" => stripeService.getPaymentIntent(apiKey, id)
                  case "price" => stripeService.getPrice(apiKey, id)
                  case "product" => stripeService.getProduct(apiKey, id)
                  case "refund" => stripeService.getRefund(apiKey, id)
                  case "subscription" => stripeService.getSubscription(apiKey, id)
                  case other =>
                    logger.info("Ignoring object type: " + other + " for id: " + id)
                    Future(None)
                })

                raw.foreach { raw =>
                  val _ = await(rawStripeObjectService.create(Seq(RawStripeObjectService.CreateData(
                    id = id,
                    stripeAccountId = event.stripeAccountId,
                    liveMode = event.liveMode,
                    objectType = objectType,
                    rawJson = raw.toString()
                  ))))

                  // Importing the customer balance transaction when the customer is involved.
                  val customerId = objectType match {
                    case "customer" => Some(id)
                    case "invoice" => Some((raw \ "customer").as[String])
                    case "credit_note" => Some((raw \ "customer").as[String])
                    case _ => None
                  }
                  customerId.foreach { customerId =>
                    importCustomerBalanceTransaction(apiKey, event.stripeAccountId, event.liveMode, customerId)
                  }
                }
              }
            }

            await(stripeEventService.incrementProcessedCount(event.id))
          }
        }))

        maxCreatedAt = events.lastOption.map { e => e.createdAt }
        maxId = events.lastOption.map { e => e.id }

        val isTooOld = maxCreatedAt.exists { c => c.isBefore(minCreatedAt) }

        done = maxCreatedAt.isEmpty || maxId.isEmpty || events.lastOption.exists { e => e.processedCount > 0 } || isTooOld
      }
    } finally {
      executors.shutdown()
    }
  }

  private def importCustomerBalanceTransaction(apiKey: String, accountId: String, liveMode: Boolean, customerId: String): Unit = {
    val job = await(stripeImporterJobService.getActive(StripeImporterJob.JobType.Batch, accountId, liveMode)) match {
      case Some(job) => job
      case None => await(stripeImporterJobService.create(StripeImporterJob.JobType.Batch, accountId, liveMode))
    }

    importAll(job, "customer_balance_transaction", Some(customerId)) { (startingAfter, endingBefore) =>
      await(stripeService.listCustomerBalanceTransactions(apiKey, customerId, startingAfter, endingBefore))
    }
  }
}

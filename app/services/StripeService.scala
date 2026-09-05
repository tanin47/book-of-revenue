package services

import framework.{ExternalServiceException, Helpers, PlayConfig}
import io.github.bucket4j.Bucket
import play.api.Logger
import play.api.libs.json.JsObject
import play.api.libs.ws.*

import java.time.Duration
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

object StripeService {
  case class ListResult[T](
    items: Seq[T],
    hasMore: Boolean
  )

  case class TestClock(
    id: String,
    name: String,
    frozenTime: Long,
    status: String
  )

  val INVOICE_EXPANDS = Seq(
    "lines.data.pretax_credit_amounts",
    "payments",
    "payments.data.payment",
  )
  val INVOICE_PAYMENT_EXPANDS = Seq(
    "payment.charge",
    "payment.charge.balance_transaction",
    "payment.payment_intent.latest_charge",
    "payment.payment_intent.latest_charge.balance_transaction",
    "payment.payment_record",
  )
  val SUBSCRIPTION_EXPANDS = Seq(
    "discounts.coupon",
    "default_tax_rates",
    "items.data.tax_rates",
  )
  val CREDIT_NOTE_EXPANDS = Seq(
    "customer_balance_transaction",
  )
  val INVOICE_ITEM_EXPANDS = Seq(
    "discounts.coupon",
    "tax_rates",
  )
  val CHARGE_EXPANDS = Seq(
    "balance_transaction",
  )
  val REFUND_EXPANDS = Seq(
    "balance_transaction",
    "failure_balance_transaction",
  )
  val DISPUTE_EXPANDS = Seq(
    "balance_transactions",
  )
  val PRICE_EXPANDS = Seq("tiers")
  val CREDIT_BALANCE_TRANSACTION_EXPANDS = Seq("credit_grant")

  val tokenBucket: Bucket = Bucket.builder()
    .addLimit { limit => limit.capacity(10).refillGreedy(10, Duration.ofSeconds(1)) }
    .build();
}

@Singleton
class StripeService @Inject() (
  ws: WSClient,
  config: PlayConfig
)(implicit ec: ExecutionContext) {

  import StripeService.*

  private val logger = Logger(getClass)

  def getCustomer(apiKey: String, id: String): Future[Option[JsObject]] = retry {
    get(s"/v1/customers/$id", apiKey)
  }

  def listCoupons(apiKey: String, startingAfter: Option[String], endingBefore: Option[String]): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/coupons",
      params = Map.empty,
      startingAfter = startingAfter,
      endingBefore = endingBefore
    )
  }
  def getCoupon(apiKey: String, id: String): Future[Option[JsObject]] = retry {
    get(s"/v1/coupons/$id", apiKey)
  }

  def getSubscription(apiKey: String, id: String): Future[Option[JsObject]] = retry {
    get(s"/v1/subscriptions/$id", apiKey, SUBSCRIPTION_EXPANDS)
  }

  def listSubscriptions(apiKey: String, customerId: Option[String], startingAfter: Option[String] = None, endingBefore: Option[String] = None): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/subscriptions",
      params = Map("status" -> "all") ++
        customerId.map { "customer" -> _ }.toMap,
      startingAfter = startingAfter,
      endingBefore = endingBefore,
      objExpands = SUBSCRIPTION_EXPANDS
    )
  }

  def listCustomers(apiKey: String, testClockId: Option[String], startingAfter: Option[String], endingBefore: Option[String]): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/customers",
      params = testClockId.map { "test_clock" -> _ }.toMap,
      startingAfter = startingAfter,
      endingBefore = endingBefore
    )
  }

  def listTestClocks(apiKey: String, startingAfter: Option[String], endingBefore: Option[String]): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/test_helpers/test_clocks",
      params = Map.empty,
      startingAfter = startingAfter,
      endingBefore = endingBefore,
    )
  }

  def getTestClock(apiKey: String, testClockId: String): Future[Option[TestClock]] = retry {
    makeWs(s"/v1/test_helpers/test_clocks/$testClockId", apiKey)
      .withMethod("GET")
      .get()
      .map { resp =>
        if (resp.status >= 200 && resp.status < 300) {
          val json = resp.json
          Some(TestClock(
            id = (json \ "id").as[String],
            name = (json \ "name").as[String],
            frozenTime = (json \ "frozen_time").as[Long],
            status = (json \ "status").as[String]
          ))
        } else if (resp.status == 404) {
          None
        } else {
          throw new ExternalServiceException(resp.status, resp.body)
        }
      }
  }

  def getPrice(apiKey: String, priceId: String): Future[Option[JsObject]] = retry {
    get(s"/v1/prices/$priceId", apiKey, PRICE_EXPANDS)
  }
  def listPrices(apiKey: String, startingAfter: Option[String], endingBefore: Option[String]): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/prices",
      params = Map.empty,
      startingAfter = startingAfter,
      endingBefore = endingBefore,
      objExpands = PRICE_EXPANDS
    )
  }

  def getProduct(apiKey: String, productId: String): Future[Option[JsObject]] = retry {
    get(s"/v1/products/$productId", apiKey)
  }
  def listProducts(apiKey: String, startingAfter: Option[String], endingBefore: Option[String]): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/products",
      params = Map.empty,
      startingAfter = startingAfter,
      endingBefore = endingBefore
    )
  }


  def getInvoice(apiKey: String, invoiceId: String): Future[Option[JsObject]] = retry {
    get(s"/v1/invoices/$invoiceId", apiKey, INVOICE_EXPANDS)
  }

  def listEvents(apiKey: String, startingAfter: Option[String], endingBefore: Option[String]): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/events",
      params = Map.empty,
      startingAfter = startingAfter,
      endingBefore = endingBefore,
    )
  }

  def listInvoices(apiKey: String, customerId: Option[String], startingAfter: Option[String], endingBefore: Option[String] = None): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/invoices",
      params = customerId.map { "customer" -> _ }.toMap,
      startingAfter = startingAfter,
      endingBefore = endingBefore,
      objExpands = INVOICE_EXPANDS
    )
  }

  def getCreditBalanceTransaction(apiKey: String, id: String): Future[Option[JsObject]] = retry {
    get(s"/v1/billing/credit_balance_transactions/$id", apiKey, CREDIT_BALANCE_TRANSACTION_EXPANDS)
  }
  def listCreditBalanceTransactions(apiKey: String, customerId: String, startingAfter: Option[String], endingBefore: Option[String]): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/billing/credit_balance_transactions",
      params = Map("customer" -> customerId),
      startingAfter = startingAfter,
      endingBefore = endingBefore,
      objExpands = CREDIT_BALANCE_TRANSACTION_EXPANDS
    )
  }

  def getCreditGrant(apiKey: String, id: String): Future[Option[JsObject]] = retry {
    get(s"/v1/billing/credit_grants/$id", apiKey)
  }
  def listCreditGrants(apiKey: String, customerId: Option[String], startingAfter: Option[String], endingBefore: Option[String]): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/billing/credit_grants",
      params = customerId.map { "customer" -> _ }.toMap,
      startingAfter = startingAfter,
      endingBefore = endingBefore
    )
  }

  def getCreditNote(apiKey: String, id: String): Future[Option[JsObject]] = retry {
    get(s"/v1/credit_notes/$id", apiKey, CREDIT_NOTE_EXPANDS)
  }

  def listCreditNotes(apiKey: String, customerId: Option[String], startingAfter: Option[String], endingBefore: Option[String] = None): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/credit_notes",
      params = customerId.map { "customer" -> _ }.toMap,
      startingAfter = startingAfter,
      endingBefore = endingBefore,
      objExpands = CREDIT_NOTE_EXPANDS
    )
  }

  def getInvoiceItem(apiKey: String, id: String): Future[Option[JsObject]] = retry {
    get(s"/v1/invoiceitems/$id", apiKey, INVOICE_ITEM_EXPANDS)
  }

  def listInvoiceItems(apiKey: String, customerId: Option[String], startingAfter: Option[String], endingBefore: Option[String] = None): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/invoiceitems",
      params = customerId.map { "customer" -> _ }.toMap,
      startingAfter = startingAfter,
      endingBefore = endingBefore,
      objExpands = INVOICE_ITEM_EXPANDS
    )
  }

  def getCharge(apiKey: String, id: String): Future[Option[JsObject]] = retry {
    get(s"/v1/charges/$id", apiKey, CHARGE_EXPANDS)
  }
  def listCharges(apiKey: String, customerId: Option[String], startingAfter: Option[String], endingBefore: Option[String]): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/charges",
      params = customerId.map("customer" -> _).toMap,
      startingAfter = startingAfter,
      endingBefore = endingBefore,
      objExpands = CHARGE_EXPANDS
    )
  }

  def getInvoicePayment(apiKey: String, id: String): Future[Option[JsObject]] = retry {
    get(
      s"/v1/invoice_payments/$id",
      apiKey,
      expands = INVOICE_PAYMENT_EXPANDS
    )
  }
  def getPaymentIntent(apiKey: String, id: String): Future[Option[JsObject]] = retry {
    get(s"/v1/payment_intents/$id", apiKey)
  }
  def listPaymentIntents(apiKey: String, customerId: Option[String], startingAfter: Option[String], endingBefore: Option[String]): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/payment_intents",
      params = customerId.map("customer" -> _).toMap,
      startingAfter = startingAfter,
      endingBefore = endingBefore
    )
  }

  def getRefund(apiKey: String, id: String): Future[Option[JsObject]] = retry {
    get(s"/v1/refunds/$id", apiKey, REFUND_EXPANDS)
  }
  def listRefunds(apiKey: String, startingAfter: Option[String], endingBefore: Option[String]): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/refunds",
      params = Map.empty,
      startingAfter = startingAfter,
      endingBefore = endingBefore,
      objExpands = REFUND_EXPANDS
    )
  }

  def getDispute(apiKey: String, id: String): Future[Option[JsObject]] = retry {
    get(s"/v1/disputes/$id", apiKey, DISPUTE_EXPANDS)
  }
  def listDisputes(apiKey: String,
    chargeId: Option[String],
    startingAfter: Option[String],
    endingBefore: Option[String],
  ): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/disputes",
      params = chargeId.map("charge" -> _).toMap,
      startingAfter = startingAfter,
      endingBefore = endingBefore,
      objExpands = DISPUTE_EXPANDS
    )
  }

  def listBalanceTransactions(apiKey: String, startingAfter: Option[String], endingBefore: Option[String]): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = "/v1/balance_transactions",
      params = Map.empty,
      startingAfter = startingAfter,
      endingBefore = endingBefore
    )
  }

  def listCustomerBalanceTransactions(apiKey: String, customerId: String, startingAfter: Option[String], endingBefore: Option[String] = None): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = s"/v1/customers/$customerId/balance_transactions",
      params = Map.empty,
      startingAfter = startingAfter,
      endingBefore = endingBefore
    )
  }

  def listMeterEventSummaries(apiKey: String,
    meterId: String,
    customerId: String,
    startTime: Long,
    endTime: Long,
    startingAfter: Option[String],
    endingBefore: Option[String] = None
  ): Future[ListResult[JsObject]] = retry {
    list(apiKey = apiKey,
      path = s"/v1/billing/meters/$meterId/event_summaries",
      params = Map(
        "customer" -> customerId,
        "start_time" -> startTime.toString,
        "end_time" -> endTime.toString,
        "value_grouping_window" -> "hour"
      ),
      startingAfter = startingAfter,
      endingBefore = endingBefore
    )
  }

  def getBalance(apiKey: String): Future[Option[JsObject]] = retry {
    get("/v1/balance", apiKey)
  }

  def getAccount(apiKey: String): Future[Option[JsObject]] = retry {
    get("/v1/account", apiKey)
  }

  private[this] def get(
    path: String,
    apiKey: String,
    expands: Seq[String] = Seq.empty
  ): Future[Option[JsObject]] = {
    makeWs(path, apiKey)
      .withMethod("GET")
      .withQueryStringParameters(expands.map("expand[]" -> _)*)
      .get()
      .map { resp =>
        if (resp.status >= 200 && resp.status < 300) {
          Some(resp.json.as[JsObject])
        } else if (resp.status == 404) {
          None
        } else {
          throw new ExternalServiceException(resp.status, resp.body)
        }
      }
  }

  private[this] def list(
    apiKey: String,
    path: String,
    params: Map[String, String],
    startingAfter: Option[String],
    endingBefore: Option[String],
    limit: Int = 100,
    listExpands: Seq[String] = Seq.empty,
    objExpands: Seq[String] = Seq.empty
  ): Future[ListResult[JsObject]] = {
    val getParams = (listExpands ++ objExpands.map("data." + _)).map { "expand[]" -> _ } ++
      Seq("limit" -> limit.toString) ++
      startingAfter.map("starting_after" -> _) ++
      endingBefore.map("ending_before" -> _) ++
      params
    logger.debug(s"GET $path $getParams")
    makeWs(path, apiKey)
      .withMethod("GET")
      .withQueryStringParameters(getParams*)
      .get()
      .map { resp =>
        if (resp.status >= 200 && resp.status < 300) {
          val json = resp.json

          ListResult(
            items = (json \ "data").as[Seq[JsObject]],
            hasMore = (json \ "has_more").as[Boolean]
          )
        } else {
          throw new ExternalServiceException(resp.status, resp.body)
        }
      }
  }

  private[this] def makeWs(path: String, apiKey: String): WSRequest = {
    ws
      .url(s"https://api.stripe.com$path")
      .withAuth(apiKey, "", WSAuthScheme.BASIC)
  }

  private[this] def retry[T](fn: => Future[T]): Future[T] = {
    def wrapped(retryCount: Int): Future[T] = {
      if (tokenBucket.tryConsume(1)) {
        fn.recoverWith {
          case e: ExternalServiceException =>
            if (e.status == 429 && retryCount > 0) {
              logger.warn(s"Encountered 429, retrying in soon")
              Helpers.sleep(700 + Random.nextInt(500)).flatMap { _ => wrapped(retryCount - 1) }
            } else {
              throw e
            }
        }
      } else {
        Helpers.sleep(500 + Random.nextInt(500)).flatMap { _ => wrapped(retryCount - 1) }
      }
    }

    wrapped(10)
  }
}

package background

import database.models.RawStripeObject
import database.services.{BalanceTransactionService, ChargeService, CouponService, CreditBalanceTransactionService, CreditGrantService, CreditNoteLineItemPretaxCreditAmountService, CreditNoteLineItemService, CreditNoteLineItemTaxService, CreditNoteRefundService, CreditNoteService, CustomerBalanceTransactionService, CustomerService, DiscountService, DisputeService, InvoiceItemService, InvoiceLineItemDiscountAmountService, InvoiceLineItemPretaxCreditAmountService, InvoiceLineItemService, InvoiceLineItemTaxService, InvoicePaymentService, InvoiceService, MeterEventSummaryService, PaymentIntentService, PriceService, PriceTierService, ProductService, RawStripeObjectService, RefundService, SubscriptionItemService, SubscriptionService, TaxRateService, TrackedExceptionService}
import framework.Helpers.await
import framework.Instant
import org.jobrunr.jobs.lambdas.{JobRequest, JobRequestHandler}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsLookupResult, JsObject, Json}
import play.api.{Environment, Logger, Mode, Play}

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

case class StripeNormalizerRequest() extends JobRequest {
  def getJobRequestHandler(): Class[StripeNormalizer] = classOf[StripeNormalizer]
}


object StripeNormalizer {
  def main(args: Array[String]): Unit = {
    val app = GuiceApplicationBuilder(Environment.simple(mode = Mode.Dev)).build()

    Play.start(app)
    val handler = app.injector.instanceOf[StripeNormalizer]
    handler.run(StripeNormalizerRequest())
  }
}

@Singleton
class StripeNormalizer @Inject() (
  rawStripeObjectService: RawStripeObjectService,
  invoiceService: InvoiceService,
  invoiceItemService: InvoiceItemService,
  invoiceLineItemService: InvoiceLineItemService,
  chargeService: ChargeService,
  paymentIntentService: PaymentIntentService,
  invoicePaymentService: InvoicePaymentService,
  disputeService: DisputeService,
  refundService: RefundService,
  balanceTransactionService: BalanceTransactionService,
  customerService: CustomerService,
  customerBalanceTransactionService: CustomerBalanceTransactionService,
  subscriptionService: SubscriptionService,
  subscriptionItemService: SubscriptionItemService,
  priceService: PriceService,
  productService: ProductService,
  priceTierService: PriceTierService,
  invoiceLineItemDiscountAmountService: InvoiceLineItemDiscountAmountService,
  invoiceLineItemTaxService: InvoiceLineItemTaxService,
  invoiceLineItemPretaxCreditAmountService: InvoiceLineItemPretaxCreditAmountService,
  couponService: CouponService,
  discountService: DiscountService,
  taxRateService: TaxRateService,
  creditBalanceTransactionService: CreditBalanceTransactionService,
  creditGrantService: CreditGrantService,
  creditNoteService: CreditNoteService,
  creditNoteLineItemService: CreditNoteLineItemService,
  creditNoteRefundService: CreditNoteRefundService,
  creditNoteLineItemTaxService: CreditNoteLineItemTaxService,
  creditNoteLineItemPretaxCreditAmountService: CreditNoteLineItemPretaxCreditAmountService,
  meterEventSummaryService: MeterEventSummaryService,
  trackedExceptionService: TrackedExceptionService
)(implicit ec: ExecutionContext) extends BaseJobRequestHandler[StripeNormalizerRequest](trackedExceptionService) {
  private[this] val logger = Logger(getClass)

  def run2(req: StripeNormalizerRequest): Unit = {
    var maxSyncedAt: Option[Instant] = None
    var maxId: Option[String] = None
    var done = false

    while (!done) {
      val objects = await(rawStripeObjectService.getUnprocessed(maxSyncedAt, maxId))
      logger.info(s"Normalizing ${objects.size} objects. First object: ${objects.headOption.map(_.id)}")

      objects.foreach { obj =>
        val json = Json.parse(obj.rawJson).as[JsObject]
        try {
          (json \ "object").as[String] match {
            case "balance_transaction" => normalizeBalanceTransaction(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "billing.credit_balance_transaction" => normalizeCreditBalanceTransaction(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "billing.credit_grant" => normalizeCreditGrant(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "billing.meter_event_summary" => normalizeMeterEventSummary(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "charge" => normalizeCharge(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "coupon" => normalizeCoupon(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "credit_note" => normalizeCreditNote(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "customer" => normalizeCustomer(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "customer_balance_transaction" => normalizeCustomerBalanceTransaction(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "dispute" => normalizeDispute(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "invoice" => normalizeInvoice(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "invoice_payment" => normalizeInvoicePayment(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "invoiceitem" => normalizeInvoiceItem(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "payment_intent" => normalizePaymentIntent(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "price" => normalizePrice(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "product" => normalizeProduct(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "refund" => normalizeRefund(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case "subscription" => normalizeSubscription(json, obj.syncedAt, obj.stripeAccountId, obj.liveMode)
            case other => logger.info("Ignoring object of type " + other + " (id: " + (json \ "id").asOpt[String] + ")")
          }
        } catch { case e: Exception =>
          logger.error(s"Error normalizing object ${obj.id} (checksum: ${obj.checksum}): ${obj.rawJson}")
          throw e
        }

        await(rawStripeObjectService.incrementProcessedCount(obj.id, obj.checksum))
      }

      maxSyncedAt = objects.lastOption.map { e => e.syncedAt }
      maxId = objects.lastOption.map { e => e.id }

      done = maxSyncedAt.isEmpty || maxId.isEmpty || objects.lastOption.exists { e => e.processedCount > 0 }
    }
  }

  private[this] def normalizeMeterEventSummary(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    val _ = await(meterEventSummaryService.create(MeterEventSummaryService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      aggregatedValue = (json \ "aggregated_value").as[Long],
      meterId = (json \ "meter").as[String],
      customerId = (json \ "customer").as[String],
      startTime = Instant.ofEpochSecond((json \ "start_time").as[Long]),
      endTime = Instant.ofEpochSecond((json \ "end_time").as[Long]),
      syncedAt = syncedAt
    )))
  }

  private[this] def normalizePaymentIntent(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    // `latest_charge` is either the id or, when expanded, the full object.
    val latestCharge = normalizeExpandableRef(json \ "latest_charge") { chargeJson => normalizeCharge(chargeJson, syncedAt, stripeAccountId, liveMode) }

    val _ = await(paymentIntentService.create(PaymentIntentService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      customerId = (json \ "customer").asOpt[String],
      amount = (json \ "amount").as[Long],
      currency = (json \ "currency").as[String],
      description = (json \ "description").asOpt[String],
      latestCharge = latestCharge,
      syncedAt = syncedAt
    )))
  }

  private[this] def normalizeCharge(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    val charge = await(chargeService.create(ChargeService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      // `balance_transaction` is either the id or, when expanded, the full object.
      balanceTransactionId = normalizeExpandableRef(json \ "balance_transaction") { btJson => normalizeBalanceTransaction(btJson, syncedAt, stripeAccountId, liveMode) },
      customerId = (json \ "customer").asOpt[String],
      amount = (json \ "amount").as[Long],
      currency = (json \ "currency").as[String],
      description = (json \ "description").asOpt[String],
      disputed = (json \ "disputed").as[Boolean],
      refunded = (json \ "refunded").as[Boolean],
      amountRefunded = (json \ "amount_refunded").asOpt[Long],
      paymentIntentId = (json \ "payment_intent").asOpt[String],
      created = Instant.ofEpochSecond((json \ "created").as[Long]),
      status = (json \ "status").as[String],
      syncedAt = syncedAt
    )))
  }

  private[this] def normalizeRefund(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    val _ = await(refundService.create(RefundService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      // `balance_transaction` and `failure_balance_transaction` are either the id or, when expanded, the full object.
      balanceTransactionId = normalizeExpandableRef(json \ "balance_transaction") { btJson => normalizeBalanceTransaction(btJson, syncedAt, stripeAccountId, liveMode) },
      failureBalanceTransactionId = normalizeExpandableRef(json \ "failure_balance_transaction") { btJson => normalizeBalanceTransaction(btJson, syncedAt, stripeAccountId, liveMode) },
      amount = (json \ "amount").as[Long],
      currency = (json \ "currency").as[String],
      chargeId = (json \ "charge").asOpt[String],
      paymentIntentId = (json \ "payment_intent").asOpt[String],
      status = (json \ "status").as[String],
      createdAt = Instant.ofEpochSecond((json \ "created").as[Long]),
      syncedAt = syncedAt
    )))
  }

  private[this] def normalizeDispute(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    val _ = await(disputeService.create(DisputeService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      // `balance_transactions` contains either ids or, when expanded, the full objects.
      balanceTransactionIds = normalizeExpandableRefs(json \ "balance_transactions") { btJson => normalizeBalanceTransaction(btJson, syncedAt, stripeAccountId, liveMode) },
      amount = (json \ "amount").as[Long],
      currency = (json \ "currency").as[String],
      chargeId = (json \ "charge").asOpt[String],
      paymentIntentId = (json \ "payment_intent").asOpt[String],
      status = (json \ "status").as[String],
      createdAt = Instant.ofEpochSecond((json \ "created").as[Long]),
      syncedAt = syncedAt
    )))
  }

  private[this] def normalizeBalanceTransaction(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    val _ = await(balanceTransactionService.create(BalanceTransactionService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      amount = (json \ "amount").as[Long],
      currency = (json \ "currency").as[String],
      description = (json \ "description").asOpt[String].getOrElse(""),
      feeAmount = (json \ "fee").as[Long],
      netAmount = (json \ "net").as[Long],
      status = (json \ "status").as[String],
      `type` = (json \ "type").as[String],
      source = (json \ "source").asOpt[String],
      createdAt = Instant.ofEpochSecond((json \ "created").as[Long]),
      syncedAt = syncedAt
    )))
  }

  private[this] def normalizeCustomer(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    val _ = await(customerService.create(CustomerService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      name = (json \ "name").asOpt[String],
      email = (json \ "email").asOpt[String],
      syncedAt = syncedAt
    )))
  }

  private[this] def normalizeCustomerBalanceTransaction(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    val _ = await(customerBalanceTransactionService.create(CustomerBalanceTransactionService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      amount = (json \ "amount").as[Long],
      created = Instant.ofEpochSecond((json \ "created").as[Long]),
      currency = (json \ "currency").as[String],
      customerId = (json \ "customer").as[String],
      description = (json \ "description").asOpt[String],
      endingBalance = (json \ "ending_balance").as[Long],
      invoiceId = (json \ "invoice").asOpt[String],
      creditNoteId = (json \ "credit_note").asOpt[String],
      `type` = (json \ "type").as[String],
      syncedAt = syncedAt
    )))
  }

  private[this] def normalizeCreditGrant(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    // `customer` is either the id or, when expanded, the full object.
    val customerId = normalizeExpandableRef(json \ "customer") { customerJson => normalizeCustomer(customerJson, syncedAt, stripeAccountId, liveMode) }.get

    val _ = await(creditGrantService.create(CreditGrantService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      customer = customerId,
      amount = (json \ "amount" \ "monetary" \ "value").asOpt[Long],
      currency = (json \ "amount" \ "monetary" \ "currency").asOpt[String],
      category = (json \ "category").as[String],
      createdAt = Instant.ofEpochSecond((json \ "created").as[Long]),
      effectiveAt = Instant.ofEpochSecond((json \ "effective_at").as[Long]),
      expiresAt = (json \ "expires_at").asOpt[Long].map(Instant.ofEpochSecond),
      voidedAt = (json \ "voided_at").asOpt[Long].map(Instant.ofEpochSecond)
    )))
  }

  private[this] def normalizeCreditBalanceTransaction(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    val creditGrantId = normalizeExpandableRef(json \ "credit_grant") { creditGrantJson => normalizeCreditGrant(creditGrantJson, syncedAt, stripeAccountId, liveMode) }.get

    val _ = await(creditBalanceTransactionService.create(CreditBalanceTransactionService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      createdAt = Instant.ofEpochSecond((json \ "created").as[Long]),
      effectiveAt = Instant.ofEpochSecond((json \ "effective_at").as[Long]),
      `type` = (json \ "type").asOpt[String],
      creditGrantId = creditGrantId,
      creditAmount = (json \ "credit" \ "amount" \ "monetary" \ "value").asOpt[Long],
      creditCurrency = (json \ "credit" \ "amount" \ "monetary" \ "currency").asOpt[String],
      creditType = (json \ "credit" \ "type").asOpt[String],
      creditInvoiceVoidedInvoiceId = (json \ "credit" \ "credits_application_invoice_voided" \ "invoice").asOpt[String],
      creditInvoiceVoidedInvoiceLineItemId = (json \ "credit" \ "credits_application_invoice_voided" \ "invoice_line_item").asOpt[String],
      debitAmount = (json \ "debit" \ "amount" \ "monetary" \ "value").asOpt[Long],
      debitCurrency = (json \ "debit" \ "amount" \ "monetary" \ "currency").asOpt[String],
      debitType = (json \ "debit" \ "type").asOpt[String],
      debitCreditsAppliedInvoiceId = (json \ "debit" \ "credits_applied" \ "invoice").asOpt[String],
      debitCreditsAppliedInvoiceLineItemId = (json \ "debit" \ "credits_applied" \ "invoice_line_item").asOpt[String],
      syncedAt = syncedAt
    )))
  }

  private[this] def normalizeCreditNote(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    val creditNoteId = (json \ "id").as[String]

    val _ = await(creditNoteService.create(CreditNoteService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = creditNoteId,
      `type` = (json \ "type").as[String],
      invoiceId = (json \ "invoice").as[String],
      currency = (json \ "currency").as[String],
      total = (json \ "total").as[Long],
      prePaymentAmount = (json \ "pre_payment_amount").as[Long],
      // `customer_balance_transaction` is either the id or, when expanded, the full object.
      customerBalanceTransactionId = normalizeExpandableRef(json \ "customer_balance_transaction") { cbtJson => normalizeCustomerBalanceTransaction(cbtJson, syncedAt, stripeAccountId, liveMode) },
      outOfBandAmount = (json \ "out_of_band_amount").asOpt[Long],
      createdAt = Instant.ofEpochSecond((json \ "created").as[Long]),
      effectiveAt = (json \ "effective_at").asOpt[Long].map(Instant.ofEpochSecond),
      voidedAt = (json \ "voided_at").asOpt[Long].map(Instant.ofEpochSecond)
    )))

    val lines = (json \ "lines" \ "data").as[Seq[JsObject]]
    lines.zipWithIndex.foreach { case (line, index) =>
      val lineId = (line \ "id").as[String]
      val _ = await(creditNoteLineItemService.create(CreditNoteLineItemService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
        id = lineId,
        creditNoteId = creditNoteId,
        description = (line \ "description").asOpt[String],
        rank = index,
        amount = (line \ "amount").as[Long],
        `type` = (line \ "type").as[String],
        invoiceLineItemId = (line \ "invoice_line_item").asOpt[String]
      )))

      val taxes = (line \ "taxes").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
      val _ = await(creditNoteLineItemTaxService.replaceByCreditNoteLineItem(lineId, taxes.zipWithIndex.map { case (tax, taxIndex) =>
        CreditNoteLineItemTaxService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
          creditNoteLineItemId = lineId,
          rank = taxIndex,
          amount = (tax \ "amount").as[Long],
          taxBehavior = (tax \ "tax_behavior").as[String]
        )
      }))

      val pretaxCreditAmounts = (line \ "pretax_credit_amounts").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
      val _ = await(creditNoteLineItemPretaxCreditAmountService.replaceByCreditNoteLineItem(lineId, pretaxCreditAmounts.zipWithIndex.map { case (pretaxCreditAmount, pretaxCreditIndex) =>
        // `credit_balance_transaction` is either the id or, when expanded, the full object.
        val creditBalanceTransactionId = normalizeExpandableRef(pretaxCreditAmount \ "credit_balance_transaction") { cbtJson => normalizeCreditBalanceTransaction(cbtJson, syncedAt, stripeAccountId, liveMode) }
        CreditNoteLineItemPretaxCreditAmountService.CreateData(
          rank = pretaxCreditIndex,
          creditNoteLineItemId = lineId,
          amount = (pretaxCreditAmount \ "amount").as[Long],
          discountId = (pretaxCreditAmount \ "discount").asOpt[String],
          creditBalanceTransactionId = creditBalanceTransactionId,
          `type` = (pretaxCreditAmount \ "type").as[String]
        )
      }))
    }

    val refunds = (json \ "refunds" \ "data").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
    val _ = await(creditNoteRefundService.replaceByCreditNote(creditNoteId, refunds.zipWithIndex.map { case (refund, refundIndex) =>
      CreditNoteRefundService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
        creditNoteId = creditNoteId,
        rank = refundIndex,
        refundId = (refund \ "refund").asOpt[String],
        `type` = (refund \ "type").asOpt[String].getOrElse(""),
        amountRefunded = (refund \ "amount_refunded").as[Long],
        paymentRecordRefundId = (refund \ "payment_record_refund").asOpt[String]
      )
    }))
  }

  private[this] def normalizeSubscription(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    val discountIds = normalizeExpandableRefs(json \ "discounts") { discountJson => normalizeDiscount(discountJson, syncedAt, stripeAccountId, liveMode) }
    val defaultTaxRateIds = normalizeExpandableRefs(json \ "default_tax_rates") { taxRateJson => normalizeTaxRate(taxRateJson, syncedAt, stripeAccountId, liveMode) }

    val _ = await(subscriptionService.create(SubscriptionService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      customerId = (json \ "customer").as[String],
      currency = (json \ "currency").as[String],
      status = (json \ "status").as[String],
      startDate = Instant.ofEpochSecond((json \ "start_date").as[Long]),
      discountIds = discountIds.toList,
      defaultTaxRateIds = defaultTaxRateIds.toList,
      syncedAt = syncedAt
    )))

    val items = (json \ "items" \ "data").as[Seq[JsObject]]
    items.foreach { item =>
      val itemDiscountIds = normalizeExpandableRefs(item \ "discounts") { discountJson => normalizeDiscount(discountJson, syncedAt, stripeAccountId, liveMode) }
      val itemTaxRateIds = normalizeExpandableRefs(item \ "tax_rates") { taxRateJson => normalizeTaxRate(taxRateJson, syncedAt, stripeAccountId, liveMode) }

      val _ = await(subscriptionItemService.create(SubscriptionItemService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
        id = (item \ "id").as[String],
        subscriptionId = (item \ "subscription").as[String],
        priceId = (item \ "price" \ "id").as[String],
        quantity = (item \ "quantity").asOpt[Long].getOrElse(1L),
        currentPeriodEnd = Instant.ofEpochSecond((item \ "current_period_end").as[Long]),
        currentPeriodStart = Instant.ofEpochSecond((item \ "current_period_start").as[Long]),
        discountIds = itemDiscountIds.toList,
        taxRateIds = itemTaxRateIds.toList,
        syncedAt = syncedAt
      )))
    }
  }

  private[this] def normalizeProduct(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    val _ = await(productService.create(ProductService.CreateData(
      stripeAccountId = stripeAccountId,
      liveMode = liveMode,
      id = (json \ "id").as[String],
      name = (json \ "name").as[String],
      description = (json \ "description").asOpt[String],
      syncedAt = syncedAt
    )))
  }

  private[this] def normalizePrice(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    val _ = await(priceService.create(PriceService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      currency = (json \ "currency").as[String],
      productId = (json \ "product").as[String],
      `type` = (json \ "type").as[String],
      billingScheme = (json \ "billing_scheme").as[String],
      unitAmount = (json \ "unit_amount").asOpt[Long].getOrElse(0L),
      tiersMode = (json \ "tiers_mode").asOpt[String],
      recurringInterval = (json \ "recurring" \ "interval").asOpt[String],
      recurringIntervalCount = (json \ "recurring" \ "interval_count").asOpt[Int],
      recurringMeterId = (json \ "recurring" \ "meter").asOpt[String],
      recurringUsageType = (json \ "recurring" \ "usage_type").asOpt[String],
      syncedAt = syncedAt
    )))

    val priceId = (json \ "id").as[String]
    val tiers = (json \ "tiers").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
    val _ = await(priceTierService.replaceByPrice(priceId, tiers.map { tier =>
      PriceTierService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
        priceId = priceId,
        flatAmount = (tier \ "flat_amount").asOpt[Long],
        unitAmount = (tier \ "unit_amount").asOpt[Long],
        upTo = (tier \ "up_to").asOpt[Long],
        syncedAt = syncedAt
      )
    }))
  }

  private[this] def normalizeInvoiceItem(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    // `discounts` and `tax_rates` are either arrays of ids or, when expanded, arrays of full objects;
    // normalize the objects when present and keep the ids either way.
    val discountIds = normalizeExpandableRefs(json \ "discounts") { discountJson => normalizeDiscount(discountJson, syncedAt, stripeAccountId, liveMode) }
    val taxRateIds = normalizeExpandableRefs(json \ "tax_rates") { taxRateJson => normalizeTaxRate(taxRateJson, syncedAt, stripeAccountId, liveMode) }

    val _ = await(invoiceItemService.create(InvoiceItemService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      invoiceId = (json \ "invoice").asOpt[String],
      customerId = (json \ "customer").as[String],
      amount = (json \ "amount").as[Long],
      currency = (json \ "currency").as[String],
      description = (json \ "description").asOpt[String],
      startedAt = (json \ "period" \ "start").asOpt[Long].map(Instant.ofEpochSecond),
      endedAt = (json \ "period" \ "end").asOpt[Long].map(Instant.ofEpochSecond),
      discountIds = discountIds.toList,
      taxRateIds = taxRateIds.toList,
      priceId = (json \ "pricing" \ "price_details" \ "price").asOpt[String],
      productId = (json \ "pricing" \ "price_details" \ "product").asOpt[String],
      createdAt = Instant.ofEpochSecond((json \ "date").as[Long]),
      syncedAt = syncedAt
    )))
  }

  // A Stripe reference field is either an id string or, when expanded, the full object. Normalize the
  // expanded object (if present) and return the id either way.
  private[this] def normalizeExpandableRef(ref: JsLookupResult)(normalize: JsObject => Unit): Option[String] = {
    ref.asOpt[String].orElse {
      ref.asOpt[JsObject].map { obj =>
        normalize(obj)
        (obj \ "id").as[String]
      }
    }
  }

  // Like normalizeExpandableRef, but for an array of ids or expanded objects.
  private[this] def normalizeExpandableRefs(ref: JsLookupResult)(normalize: JsObject => Unit): Seq[String] = {
    ref.asOpt[Seq[JsObject]] match {
      case Some(objs) => objs.map { obj => normalize(obj); (obj \ "id").as[String] }
      case None => ref.asOpt[Seq[String]].getOrElse(Seq.empty)
    }
  }

  private[this] def normalizeDiscount(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    // A discount embeds its coupon as a full object.
    val couponId = normalizeExpandableRef(json \ "coupon") { couponJson => normalizeCoupon(couponJson, syncedAt, stripeAccountId, liveMode) }
    val _ = await(discountService.create(DiscountService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      couponId = couponId
    )))
  }

  private[this] def normalizeCoupon(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    val _ = await(couponService.create(CouponService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      amountOff = (json \ "amount_off").asOpt[Long],
      currency = (json \ "currency").asOpt[String],
      percentOff = (json \ "percent_off").asOpt[Double]
    )))
  }

  private[this] def normalizeTaxRate(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    val _ = await(taxRateService.create(TaxRateService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      inclusive = (json \ "inclusive").as[Boolean],
      percentage = (json \ "percentage").as[Double],
      flatAmount = (json \ "flat_amount" \ "amount").asOpt[Long],
      flatAmountCurrency = (json \ "flat_amount" \ "currency").asOpt[String],
      rateType = (json \ "rate_type").asOpt[String]
    )))
  }

  private[this] def normalizeInvoice(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    val statusTransitions = (json \ "status_transitions").as[JsObject]

    val invoice = await(invoiceService.create(InvoiceService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      customerId = (json \ "customer").as[String],
      number = (json \ "number").asOpt[String],
      total = (json \ "total").as[Long],
      amountPaid = (json \ "amount_paid").as[Long],
      amountOverpaid = (json \ "amount_overpaid").as[Long],
      amountRemaining = (json \ "amount_remaining").as[Long],
      currency = (json \ "currency").as[String],
      finalizedAt = (statusTransitions \ "finalized_at").asOpt[Long].map(Instant.ofEpochSecond),
      paidAt = (statusTransitions \ "paid_at").asOpt[Long].map(Instant.ofEpochSecond),
      dueAt = (json \ "due_date").asOpt[Long].map(Instant.ofEpochSecond),
      markedUncollectibleAt = (statusTransitions \ "marked_uncollectible_at").asOpt[Long].map(Instant.ofEpochSecond),
      voidedAt = (statusTransitions \ "voided_at").asOpt[Long].map(Instant.ofEpochSecond),
      startingBalance = (json \ "starting_balance").asOpt[Long],
      endingBalance = (json \ "ending_balance").asOpt[Long],
      status = (json \ "status").as[String],
      syncedAt = syncedAt
    )))

    val lines = (json \ "lines" \ "data").as[Seq[JsObject]]
    lines.zipWithIndex.foreach { case (line, index) =>
      val lineId = (line \ "id").as[String]
      val _ = await(invoiceLineItemService.create(InvoiceLineItemService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
        id = lineId,
        invoiceId = (line \ "invoice").as[String],
        description = (line \ "description").asOpt[String],
        amount = (line \ "amount").as[Long],
        currency = (line \ "currency").as[String],
        startedAt = (line \ "period" \ "start").asOpt[Long].map(Instant.ofEpochSecond),
        endedAt = (line \ "period" \ "end").asOpt[Long].map(Instant.ofEpochSecond),
        rank = index,
        invoiceItemId = (line \ "parent" \ "invoice_item_details" \ "invoice_item").asOpt[String].orElse(
          (line \ "parent" \ "subscription_item_details" \ "invoice_item").asOpt[String]
        ),
        subscriptionItemId = (line \ "parent" \ "subscription_item_details" \ "subscription_item").asOpt[String],
        priceId = (line \ "pricing" \ "price_details" \ "price").asOpt[String],
        pricingUnitAmountDecimal = (line \ "pricing" \ "unit_amount_decimal").asOpt[String],
        customerId = (json \ "customer").as[String],
        syncedAt = syncedAt
      )))

      val discountAmounts = (line \ "discount_amounts").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
      val _ = await(invoiceLineItemDiscountAmountService.replaceByInvoiceLineItem(lineId, discountAmounts.zipWithIndex.map { case (discountAmount, discountIndex) =>
        InvoiceLineItemDiscountAmountService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
          rank = discountIndex,
          invoiceLineItemId = lineId,
          amount = (discountAmount \ "amount").as[Long],
          discountId = (discountAmount \ "discount").as[String]
        )
      }))

      val taxes = (line \ "taxes").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
      val _ = await(invoiceLineItemTaxService.replaceByInvoiceLineItem(lineId, taxes.zipWithIndex.map { case (tax, taxIndex) =>
        InvoiceLineItemTaxService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
          rank = taxIndex,
          invoiceLineItemId = lineId,
          amount = (tax \ "amount").as[Long],
          taxBehaviour = (tax \ "tax_behavior").as[String],
          taxRateId = (tax \ "tax_rate_details" \ "tax_rate").asOpt[String]
        )
      }))

      val pretaxCreditAmounts = (line \ "pretax_credit_amounts").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
      val _ = await(invoiceLineItemPretaxCreditAmountService.replaceByInvoiceLineItem(lineId, pretaxCreditAmounts.zipWithIndex.map { case (pretaxCreditAmount, pretaxCreditIndex) =>
        // `credit_balance_transaction` is either the id or, when expanded, the full object.
        val creditBalanceTransactionId = normalizeExpandableRef(pretaxCreditAmount \ "credit_balance_transaction") { cbtJson => normalizeCreditBalanceTransaction(cbtJson, syncedAt, stripeAccountId, liveMode) }
        InvoiceLineItemPretaxCreditAmountService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
          rank = pretaxCreditIndex,
          invoiceLineItemId = lineId,
          amount = (pretaxCreditAmount \ "amount").as[Long],
          discountId = (pretaxCreditAmount \ "discount").asOpt[String],
          creditBalanceTransactionId = creditBalanceTransactionId,
          `type` = (pretaxCreditAmount \ "type").as[String]
        )
      }))
    }

    val payments = (json \ "payments" \ "data").asOpt[Seq[JsObject]]
    payments.foreach { _.foreach { payment => normalizeInvoicePayment(payment, syncedAt, stripeAccountId, liveMode) } }
  }

  private[this] def normalizeInvoicePayment(json: JsObject, syncedAt: Instant, stripeAccountId: String, liveMode: Boolean): Unit = {
    // `charge`, `payment_intent`, and `payment_record` are either ids or, when expanded, full objects.
    val chargeId = normalizeExpandableRef(json \ "payment" \ "charge") { chargeJson => normalizeCharge(chargeJson, syncedAt, stripeAccountId, liveMode) }
    val paymentIntentId = normalizeExpandableRef(json \ "payment" \ "payment_intent") { paymentIntentJson => normalizePaymentIntent(paymentIntentJson, syncedAt, stripeAccountId, liveMode) }
    // `payment_record` is not normalized on its own; keep the id either way.
    val paymentRecordId = normalizeExpandableRef(json \ "payment" \ "payment_record") { _ => () }

    val _ = await(invoicePaymentService.create(InvoicePaymentService.CreateData(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
      id = (json \ "id").as[String],
      amountPaid = (json \ "amount_paid").asOpt[Long],
      amountRequested = (json \ "amount_requested").asOpt[Long],
      currency = (json \ "currency").as[String],
      invoiceId = (json \ "invoice").as[String],
      chargeId = chargeId,
      paymentIntentId = paymentIntentId,
      paymentRecordId = paymentRecordId,
      paymentType = (json \ "payment" \ "payment_type").asOpt[String],
      createdAt = Instant.ofEpochSecond((json \ "created").as[Long]),
      canceledAt = (json \ "canceled_at").asOpt[Long].map(Instant.ofEpochSecond),
      paidAt = (json \ "status_transitions" \ "paid_at").asOpt[Long].map(Instant.ofEpochSecond),
      status = (json \ "status").as[String],
      syncedAt = syncedAt
    )))
  }
}

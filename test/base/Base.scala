package base

import ch.qos.logback.classic.Level
import database.models.*
import database.services.*
import framework.{Instant, PlayConfig}
import mockws.MockWSHelpers.Action
import org.jobrunr.storage.StorageProvider
import org.jobrunr.storage.StorageProviderUtils.DatabaseOptions
import org.openqa.selenium.StaleElementReferenceException
import org.scalatest.exceptions.TestFailedException
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, BeforeAndAfterEach}
import org.slf4j.LoggerFactory
import play.api.db.evolutions.EvolutionsApi
import play.api.db.slick.DatabaseConfigProvider
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.mvc.Results.Ok
import play.api.test.Helpers.POST
import play.api.{Application, Configuration, Mode, inject}
import process.*
import services.ExchangeRate
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import scala.concurrent.Future

object Base {
  val PORT = 9002
  val IS_MAC: Boolean = sys.props("os.name").toLowerCase.contains("mac")

  lazy val appConfig: Map[String, Any] = Map(
    "slick.dbs.default.db.properties.url" -> "postgres://bor_dev_user:dev@localhost:5432/bor_test",
    "play.evolutions.enabled" -> false,
    "app.baseUrl" -> s"http://localhost:$PORT"
  )
}

class Base extends AnyFunSpec with BeforeAndAfter with BeforeAndAfterAll with BeforeAndAfterEach with Matchers {
  import Base.*

  lazy val app: Application = new GuiceApplicationBuilder()
    .configure(Configuration.from(appConfig))
    .in(Mode.Test)
    .overrides(inject.bind[WSClient].to[FakeOrRealWSClient])
    .build()

  lazy val dbConfigProvider: DatabaseConfigProvider = app.injector.instanceOf[DatabaseConfigProvider]
  lazy val dbConfig: DatabaseConfig[JdbcProfile] = dbConfigProvider.get[JdbcProfile]

  lazy val config: PlayConfig = app.injector.instanceOf[PlayConfig]
  lazy val stripeTestApikey: String = config.getOptString("stripe.testModeApiKeyForTest").getOrElse(sys.env("STRIPE_TEST_API_KEY"))
  lazy val ws: FakeOrRealWSClient = app.injector.instanceOf[FakeOrRealWSClient]

  lazy val userService: UserService = app.injector.instanceOf[UserService]

  lazy val rawStripeObjectService: RawStripeObjectService = app.injector.instanceOf[RawStripeObjectService]
  lazy val invoiceService: InvoiceService = app.injector.instanceOf[InvoiceService]
  lazy val invoiceLineItemService: InvoiceLineItemService = app.injector.instanceOf[InvoiceLineItemService]
  lazy val invoicePaymentService: InvoicePaymentService = app.injector.instanceOf[InvoicePaymentService]
  lazy val customerBalanceTransactionService: CustomerBalanceTransactionService = app.injector.instanceOf[CustomerBalanceTransactionService]
  lazy val paymentIntentService: PaymentIntentService = app.injector.instanceOf[PaymentIntentService]
  lazy val fileService: FileService = app.injector.instanceOf[FileService]

  var idRunner: Int = 0

  def genId(): Int = {
    idRunner += 1
    idRunner
  }

  def await[T](future: Future[T]): T = {
    import play.api.test.Helpers
    Helpers.await(future, 120, TimeUnit.SECONDS)
  }

  def resetDatabase(): Unit = {
    // Initialize play.api.db.evolutions.DefaultEvolutionsApi, so we can set the log level dynamically.
    app.injector.instanceOf[EvolutionsApi]

    // Silence the logs from evolutions
    LoggerFactory
      .getLogger("play.api.db.evolutions")
      .asInstanceOf[ch.qos.logback.classic.Logger]
      .setLevel(Level.INFO)

    {
      import framework.PostgresProfile.api.*
      val db = dbConfig.db

      val tables = await(db.run {
        sql"SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename ASC;"
          .as[String]
      })

      tables.foreach { table =>
        await(db.run {
          sqlu"""DROP TABLE IF EXISTS "#$table" CASCADE;"""
        })
      }

      app.injector.instanceOf[EvolutionsApi].applyFor("default")


      LoggerFactory
        .getLogger("org.jobrunr")
        .asInstanceOf[ch.qos.logback.classic.Logger]
        .setLevel(Level.WARN)
      app.injector.instanceOf[StorageProvider].setUpStorageProvider(DatabaseOptions.CREATE)
    }
  }

  override def beforeEach(): Unit = {
    ws.clearMockedRoutes()
    ws.addMockedRoutes {
      // Mock Sendgrid's endpoint
      case (POST, s"https://api.sendgrid.com/v3/mail/send") =>
        Action { req => Ok(Json.obj()) }
    }
    resetDatabase()
    super.beforeEach()
    Instant.mockTimeForTest(java.time.Instant.parse("2025-09-22T07:00:00Z"))
  }

  override def afterAll(): Unit = {
    await(app.stop())
  }

  private[this] val WAIT_UNTIL_TIMEOUT_MILLIS = 15000
  def waitUntil(fn: => Boolean): Unit = {
    val newFn = () => {
      try {
        fn
      } catch {
        case _: StaleElementReferenceException   => false
        case _: NoSuchElementException           => false
        case _: java.util.NoSuchElementException => false
      }
    }

    val startTime = System.currentTimeMillis()
    while ((System.currentTimeMillis() - startTime) < WAIT_UNTIL_TIMEOUT_MILLIS) {
      Thread.sleep(250)

      if (newFn()) return
    }

    throw new TestFailedException("waitUntil failed.", 1)
  }

  def makeUser(
    email: String = s"test${genId()}@random.email",
    password: String = "pass"
  ): User = {
    await(
      userService.create(
        UserService.CreateData(
          username = email,
          password = password
        )
      )
    )
  }

  def makeInvoice(
    id: String = s"in_${genId()}",
    customerId: String = "cus_1",
    number: Option[String] = None,
    total: Long = 10000,
    amountPaid: Long = 0,
    amountOverpaid: Long = 0,
    amountRemaining: Long = 0,
    currency: String = "usd",
    finalizedAt: Option[Instant] = None,
    paidAt: Option[Instant] = None,
    dueAt: Option[Instant] = None,
    markedUncollectibleAt: Option[Instant] = None,
    voidedAt: Option[Instant] = None,
    startingBalance: Option[Long] = None,
    endingBalance: Option[Long] = None,
    status: String = "open",
    syncedAt: Instant = Instant.now()
  ): Invoice = Invoice(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    customerId = customerId,
    number = number,
    total = total,
    amountPaid = amountPaid,
    amountOverpaid = amountOverpaid,
    amountRemaining = amountRemaining,
    currency = currency,
    finalizedAt = finalizedAt,
    paidAt = paidAt,
    dueAt = dueAt,
    markedUncollectibleAt = markedUncollectibleAt,
    voidedAt = voidedAt,
    startingBalance = startingBalance,
    endingBalance = endingBalance,
    status = status,
    syncedAt = syncedAt
  )

  def makeInvoiceLineItem(
    id: String = s"li_${genId()}",
    invoiceId: String = "in_1",
    description: Option[String] = None,
    amount: Long = 1000,
    currency: String = "usd",
    startedAt: Option[Instant] = None,
    endedAt: Option[Instant] = None,
    rank: Int = 0,
    invoiceItemId: Option[String] = None,
    subscriptionItemId: Option[String] = None,
    priceId: Option[String] = None,
    pricingUnitAmountDecimal: Option[String] = None,
    customerId: String = "cus_1",
    syncedAt: Instant = Instant.now()
  ): InvoiceLineItem = InvoiceLineItem(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    invoiceId = invoiceId,
    description = description,
    amount = amount,
    currency = currency,
    startedAt = startedAt,
    endedAt = endedAt,
    rank = rank,
    invoiceItemId = invoiceItemId,
    subscriptionItemId = subscriptionItemId,
    priceId = priceId,
    pricingUnitAmountDecimal = pricingUnitAmountDecimal,
    customerId = customerId,
    syncedAt = syncedAt
  )

  def makeRichInvoiceLineItem(
    base: InvoiceLineItem = makeInvoiceLineItem(),
    invoiceItem: Option[RichInvoiceItem] = None,
    subscriptionItem: Option[SubscriptionItem] = None,
    price: Option[RichPrice] = None,
    meterEventSummaries: Seq[MeterEventSummary] = Seq.empty,
    startedAtExchangeRate: ExchangeRate = ExchangeRate.sameCurrency("usd"),
    pretaxCreditAmounts: Seq[RichInvoiceLineItemPretaxCreditAmount] = Seq.empty,
    taxes: Seq[InvoiceLineItemTax] = Seq.empty,
    creditBalanceTransactionsAppliedOnVoid: Seq[RichCreditBalanceTransaction] = Seq.empty
  ): RichInvoiceLineItem = RichInvoiceLineItem(
    base = base,
    invoiceItem = invoiceItem,
    subscriptionItem = subscriptionItem,
    price = price,
    meterEventSummaries = meterEventSummaries,
    startedAtExchangeRate = startedAtExchangeRate,
    pretaxCreditAmounts = pretaxCreditAmounts,
    taxes = taxes,
    creditBalanceTransactionsAppliedOnVoid = creditBalanceTransactionsAppliedOnVoid
  )

  def makeInvoiceLineItemDiscountAmount(
    rank: Int = 0,
    invoiceLineItemId: String = s"li_${genId()}",
    amount: Long = 100,
    discountId: String = "discount_test"
  ): InvoiceLineItemDiscountAmount = InvoiceLineItemDiscountAmount(
    stripeAccountId = "",
    liveMode = false,
    rank = rank,
    invoiceLineItemId = invoiceLineItemId,
    amount = amount,
    discountId = discountId
  )

  def makeInvoiceLineItemTax(
    rank: Int = 0,
    invoiceLineItemId: String = s"li_${genId()}",
    amount: Long = 100,
    taxBehaviour: String = "exclusive",
    taxRateId: Option[String] = Some("txr_test")
  ): InvoiceLineItemTax = InvoiceLineItemTax(
    stripeAccountId = "",
    liveMode = false,
    rank = rank,
    invoiceLineItemId = invoiceLineItemId,
    amount = amount,
    taxBehaviour = taxBehaviour,
    taxRateId = taxRateId
  )

  def makeCreditNoteLineItemTax(
    rank: Int = 0,
    creditNoteLineItemId: String = s"cnli_${genId()}",
    amount: Long = 100,
    taxBehavior: String = "exclusive"
  ): CreditNoteLineItemTax = CreditNoteLineItemTax(
    stripeAccountId = "",
    liveMode = false,
    rank = rank,
    creditNoteLineItemId = creditNoteLineItemId,
    amount = amount,
    taxBehavior = taxBehavior
  )

  def makeCreditGrant(
    id: String = s"credgr_${genId()}",
    customer: String = "cus_1",
    amount: Option[Long] = None,
    currency: Option[String] = None,
    category: String = "paid",
    createdAt: Instant = Instant.now(),
    effectiveAt: Instant = Instant.now(),
    expiresAt: Option[Instant] = None,
    voidedAt: Option[Instant] = None
  ): CreditGrant = CreditGrant(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    customer = customer,
    amount = amount,
    currency = currency,
    category = category,
    createdAt = createdAt,
    effectiveAt = effectiveAt,
    expiresAt = expiresAt,
    voidedAt = voidedAt
  )

  def makeCreditBalanceTransaction(
    id: String = s"cbtxn_${genId()}",
    createdAt: Instant = Instant.now(),
    effectiveAt: Instant = Instant.now(),
    `type`: String = "credit",
    creditGrantId: String = "credgr_1",
    creditAmount: Option[Long] = None,
    creditCurrency: Option[String] = None,
    creditType: Option[String] = None,
    creditInvoiceVoidedInvoiceId: Option[String] = None,
    creditInvoiceVoidedInvoiceLineItemId: Option[String] = None,
    debitAmount: Option[Long] = None,
    debitCurrency: Option[String] = None,
    debitType: Option[String] = None,
    debitCreditsAppliedInvoiceId: Option[String] = None,
    debitCreditsAppliedInvoiceLineItemId: Option[String] = None,
    syncedAt: Instant = Instant.now(),
  ): CreditBalanceTransaction = CreditBalanceTransaction(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    createdAt = createdAt,
    effectiveAt = effectiveAt,
    `type` = Some(`type`),
    creditGrantId = creditGrantId,
    creditAmount = creditAmount,
    creditCurrency = creditCurrency,
    creditType = creditType,
    creditInvoiceVoidedInvoiceId = creditInvoiceVoidedInvoiceId,
    creditInvoiceVoidedInvoiceLineItemId = creditInvoiceVoidedInvoiceLineItemId,
    debitAmount = debitAmount,
    debitCurrency = debitCurrency,
    debitType = debitType,
    debitCreditsAppliedInvoiceId = debitCreditsAppliedInvoiceId,
    debitCreditsAppliedInvoiceLineItemId = debitCreditsAppliedInvoiceLineItemId,
    syncedAt = syncedAt
  )

  def makeRichCreditBalanceTransaction(
    base: CreditBalanceTransaction = makeCreditBalanceTransaction(),
    creditGrant: Option[CreditGrant] = None
  ): RichCreditBalanceTransaction = RichCreditBalanceTransaction(
    base = base,
    creditGrant = creditGrant
  )

  // A credit balance transaction that grants credits into a grant (credit side).
  def makeGrantedCreditBalanceTransaction(
    id: String = s"cbtxn_${genId()}",
    amount: Long = 100,
    currency: String = "usd",
    category: String = "paid",
    effectiveAt: Instant = Instant.now(),
    syncedAt: Instant = Instant.now(),
  ): RichCreditBalanceTransaction = makeRichCreditBalanceTransaction(
    base = makeCreditBalanceTransaction(
      id = id,
      `type` = "credit",
      effectiveAt = effectiveAt,
      creditAmount = Some(amount),
      creditCurrency = Some(currency),
      creditType = Some("credits_granted"),
      syncedAt = syncedAt,
    ),
    creditGrant = Some(makeCreditGrant(amount = Some(amount), currency = Some(currency), category = category)),
  )

  // A credit balance transaction that expires unused credits (debit side).
  def makeExpiredCreditBalanceTransaction(
    id: String = s"cbtxn_${genId()}",
    amount: Long = 100,
    currency: String = "usd",
    category: String = "paid",
    effectiveAt: Instant = Instant.now(),
    syncedAt: Instant = Instant.now(),
  ): RichCreditBalanceTransaction = makeRichCreditBalanceTransaction(
    base = makeCreditBalanceTransaction(
      id = id,
      `type` = "debit",
      effectiveAt = effectiveAt,
      debitAmount = Some(amount),
      debitCurrency = Some(currency),
      debitType = Some("credits_expired"),
      syncedAt = syncedAt,
    ),
    creditGrant = Some(makeCreditGrant(amount = Some(amount), currency = Some(currency), category = category)),
  )

  // A credit balance transaction that voids a grant's remaining credits (debit side).
  def makeVoidedCreditBalanceTransaction(
    id: String = s"cbtxn_${genId()}",
    amount: Long = 100,
    currency: String = "usd",
    category: String = "paid",
    effectiveAt: Instant = Instant.now(),
    syncedAt: Instant = Instant.now(),
  ): RichCreditBalanceTransaction = makeRichCreditBalanceTransaction(
    base = makeCreditBalanceTransaction(
      id = id,
      `type` = "debit",
      effectiveAt = effectiveAt,
      debitAmount = Some(amount),
      debitCurrency = Some(currency),
      debitType = Some("credits_voided"),
      syncedAt = syncedAt,
    ),
    creditGrant = Some(makeCreditGrant(amount = Some(amount), currency = Some(currency), category = category)),
  )

  def makeInvoiceLineItemPretaxCreditAmount(
    rank: Int = 0,
    invoiceLineItemId: String = s"li_${genId()}",
    amount: Long = 100,
    discountId: Option[String] = None,
    creditBalanceTransactionId: Option[String] = Some("cbtxn_test"),
    `type`: String = "credit_balance_transaction"
  ): InvoiceLineItemPretaxCreditAmount = InvoiceLineItemPretaxCreditAmount(
    stripeAccountId = "",
    liveMode = false,
    rank = rank,
    invoiceLineItemId = invoiceLineItemId,
    amount = amount,
    discountId = discountId,
    creditBalanceTransactionId = creditBalanceTransactionId,
    `type` = `type`
  )

  def makeRichInvoiceLineItemPretaxCreditAmount(
    base: InvoiceLineItemPretaxCreditAmount = makeInvoiceLineItemPretaxCreditAmount(),
    discount: Option[Discount] = None,
    creditBalanceTransaction: Option[RichCreditBalanceTransaction] = None
  ): RichInvoiceLineItemPretaxCreditAmount = RichInvoiceLineItemPretaxCreditAmount(
    base = base,
    discount = discount,
    creditBalanceTransaction = creditBalanceTransaction
  )

  def makeDiscountPretaxCreditAmount(
    rank: Int = 0,
    amount: Long = 100,
    discountId: String = "di_test"
  ): RichInvoiceLineItemPretaxCreditAmount = makeRichInvoiceLineItemPretaxCreditAmount(
    base = makeInvoiceLineItemPretaxCreditAmount(
      rank = rank,
      amount = amount,
      discountId = Some(discountId),
      creditBalanceTransactionId = None,
      `type` = "discount"
    )
  )

  def makeCoupon(
    id: String = s"coupon_${genId()}",
    amountOff: Option[Long] = None,
    currency: Option[String] = None,
    percentOff: Option[Double] = None
  ): Coupon = Coupon(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    amountOff = amountOff,
    currency = currency,
    percentOff = percentOff
  )

  def makeRichDiscount(
    id: String = s"di_${genId()}",
    coupon: Option[Coupon] = None
  ): RichDiscount = RichDiscount(
    base = Discount(stripeAccountId = "", liveMode = false, id = id, couponId = coupon.map(_.id)),
    coupon = coupon
  )

  def makeTaxRate(
    id: String = s"txr_${genId()}",
    inclusive: Boolean = false,
    percentage: Double = 0.0,
    flatAmount: Option[Long] = None,
    flatAmountCurrency: Option[String] = None,
    rateType: Option[String] = None
  ): TaxRate = TaxRate(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    inclusive = inclusive,
    percentage = percentage,
    flatAmount = flatAmount,
    flatAmountCurrency = flatAmountCurrency,
    rateType = rateType
  )

  def makeRichInvoiceItem(
    base: InvoiceItem = makeInvoiceItem(),
    discounts: Seq[RichDiscount] = Seq.empty,
    taxRates: Seq[TaxRate] = Seq.empty,
    createdAtExchangeRate: Option[ExchangeRate] = Some(ExchangeRate.sameCurrency("usd"))
  ): RichInvoiceItem = RichInvoiceItem(
    base = base,
    discounts = discounts,
    taxRates = taxRates,
    createdAtExchangeRate = createdAtExchangeRate
  )

  def makeInvoiceItem(
    id: String = s"ii_${genId()}",
    invoiceId: Option[String] = Some("in_1"),
    customerId: String = "cus_1",
    amount: Long = 1000,
    currency: String = "usd",
    description: Option[String] = None,
    startedAt: Option[Instant] = None,
    endedAt: Option[Instant] = None,
    discountIds: List[String] = List.empty,
    taxRateIds: List[String] = List.empty,
    priceId: Option[String] = None,
    productId: Option[String] = None,
    createdAt: Instant = Instant.now(),
    syncedAt: Instant = Instant.now()
  ): InvoiceItem = InvoiceItem(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    invoiceId = invoiceId,
    customerId = customerId,
    amount = amount,
    currency = currency,
    description = description,
    startedAt = startedAt,
    endedAt = endedAt,
    discountIds = discountIds,
    taxRateIds = taxRateIds,
    priceId = priceId,
    productId = productId,
    createdAt = createdAt,
    syncedAt = syncedAt
  )

  def makeRichInvoice(
    base: Invoice = makeInvoice(),
    lineItems: Seq[RichInvoiceLineItem] = Seq.empty,
    payments: Seq[RichInvoicePayment] = Seq.empty,
    customerBalanceTransactions: Seq[CustomerBalanceTransaction] = Seq.empty,
    creditNotes: Seq[RichCreditNote] = Seq.empty,
    finalizedAtExchangeRate: Option[ExchangeRate] = Some(ExchangeRate.sameCurrency("usd")),
  ): RichInvoice = {
    RichInvoice(
      base = base,
      lineItems = lineItems,
      payments = payments,
      customerBalanceTransactions = customerBalanceTransactions,
      creditNotes = creditNotes,
      finalizedAtExchangeRate = finalizedAtExchangeRate,
    )
  }

  def makeCustomerBalanceTransaction(
    id: String = s"cbt_${genId()}",
    amount: Long = 1000,
    created: Instant = Instant.now(),
    currency: String = "usd",
    customerId: String = "cus_1",
    description: Option[String] = None,
    endingBalance: Long = 1000,
    invoiceId: Option[String] = None,
    creditNoteId: Option[String] = None,
    `type`: String = "invoice_too_large",
    syncedAt: Instant = Instant.now()
  ): CustomerBalanceTransaction = CustomerBalanceTransaction(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    amount = amount,
    createdAt = created,
    currency = currency,
    customerId = customerId,
    description = description,
    endingBalance = endingBalance,
    invoiceId = invoiceId,
    creditNoteId = creditNoteId,
    `type` = `type`,
    syncedAt = syncedAt
  )

  def makeCreditNote(
    id: String = s"cn_${genId()}",
    `type`: String = "pre_payment",
    invoiceId: String = "in_1",
    currency: String = "usd",
    total: Long = 1000,
    prePaymentAmount: Long = 0,
    customerBalanceTransactionId: Option[String] = None,
    outOfBandAmount: Option[Long] = None,
    createdAt: Instant = Instant.now(),
    effectiveAt: Option[Instant] = None,
    voidedAt: Option[Instant] = None,
  ): CreditNote = CreditNote(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    `type` = `type`,
    invoiceId = invoiceId,
    currency = currency,
    total = total,
    prePaymentAmount = prePaymentAmount,
    customerBalanceTransactionId = customerBalanceTransactionId,
    outOfBandAmount = outOfBandAmount,
    createdAt = createdAt,
    effectiveAt = effectiveAt,
    voidedAt = voidedAt
  )

  def makeRichCreditNote(
    base: CreditNote = makeCreditNote(),
    customerBalanceTransaction: Option[CustomerBalanceTransaction] = None,
    lines: Seq[RichCreditNoteLineItem] = Seq.empty,
    refunds: Seq[RichCreditNoteRefund] = Seq.empty,
  ): RichCreditNote = RichCreditNote(
    base = base,
    customerBalanceTransaction = customerBalanceTransaction,
    lines = lines,
    refunds = refunds
  )

  def makeCreditNoteLineItem(
    id: String = s"cnli_${genId()}",
    creditNoteId: String = "cn_1",
    rank: Int = 0,
    amount: Long = 1000,
    `type`: String = "credit_note_line_item",
    invoiceLineItemId: Option[String] = None,
  ): CreditNoteLineItem = CreditNoteLineItem(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    description = None,
    creditNoteId = creditNoteId,
    rank = rank,
    amount = amount,
    `type` = `type`,
    invoiceLineItemId = invoiceLineItemId
  )

  def makeRichCreditNoteLineItem(
    base: CreditNoteLineItem = makeCreditNoteLineItem(),
    pretaxCreditAmounts: Seq[RichCreditNoteLineItemPretaxCreditAmount] = Seq.empty,
    taxes: Seq[CreditNoteLineItemTax] = Seq.empty,
  ): RichCreditNoteLineItem = RichCreditNoteLineItem(
    base = base,
    pretaxCreditAmounts = pretaxCreditAmounts,
    taxes = taxes
  )

  def makeCreditNoteLineItemPretaxCreditAmount(
    rank: Int = 0,
    creditNoteLineItemId: String = s"cnli_${genId()}",
    amount: Long = 100,
    discountId: Option[String] = None,
    creditBalanceTransactionId: Option[String] = Some("cbtxn_test"),
    `type`: String = "credit_balance_transaction"
  ): CreditNoteLineItemPretaxCreditAmount = CreditNoteLineItemPretaxCreditAmount(
    rank = rank,
    creditNoteLineItemId = creditNoteLineItemId,
    amount = amount,
    discountId = discountId,
    creditBalanceTransactionId = creditBalanceTransactionId,
    `type` = `type`
  )

  def makeRichCreditNoteLineItemPretaxCreditAmount(
    base: CreditNoteLineItemPretaxCreditAmount = makeCreditNoteLineItemPretaxCreditAmount(),
    discount: Option[Discount] = None,
    creditBalanceTransaction: Option[RichCreditBalanceTransaction] = None
  ): RichCreditNoteLineItemPretaxCreditAmount = RichCreditNoteLineItemPretaxCreditAmount(
    base = base,
    discount = discount,
    creditBalanceTransaction = creditBalanceTransaction
  )

  def makeCreditNoteDiscountPretaxCreditAmount(
    rank: Int = 0,
    amount: Long = 100,
    discountId: String = "di_test"
  ): RichCreditNoteLineItemPretaxCreditAmount = makeRichCreditNoteLineItemPretaxCreditAmount(
    base = makeCreditNoteLineItemPretaxCreditAmount(
      rank = rank,
      amount = amount,
      discountId = Some(discountId),
      creditBalanceTransactionId = None,
      `type` = "discount"
    )
  )

  def makeCreditNoteRefund(
    creditNoteId: String = "cn_1",
    rank: Int = 0,
    refundId: Option[String] = None,
    `type`: String = "refund",
    amountRefunded: Long = 1000,
    paymentRecordRefundId: Option[String] = None,
  ): CreditNoteRefund = CreditNoteRefund(
    stripeAccountId = "",
    liveMode = false,
    creditNoteId = creditNoteId,
    rank = rank,
    refundId = refundId,
    `type` = `type`,
    amountRefunded = amountRefunded,
    paymentRecordRefundId = paymentRecordRefundId
  )

  def makeRichCreditNoteRefund(
    base: CreditNoteRefund = makeCreditNoteRefund(),
    refund: Option[RichRefund] = None
  ): RichCreditNoteRefund = RichCreditNoteRefund(
    base = base.copy(refundId = refund.map(_.base.id)),
    refund = refund
  )

  def makeInvoicePayment(
    id: String = s"ip_${genId()}",
    amountPaid: Option[Long] = None,
    amountRequested: Option[Long] = Some(1000),
    currency: String = "usd",
    invoiceId: String = "in_1",
    chargeId: Option[String] = None,
    paymentIntentId: Option[String] = None,
    paymentRecordId: Option[String] = None,
    paymentType: Option[String] = Some("card"),
    createdAt: Instant = Instant.now(),
    canceledAt: Option[Instant] = None,
    paidAt: Option[Instant] = None,
    status: String = "succeeded",
    syncedAt: Instant = Instant.now()
  ): InvoicePayment = InvoicePayment(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    amountPaid = amountPaid,
    amountRequested = amountRequested,
    currency = currency,
    invoiceId = invoiceId,
    chargeId = chargeId,
    paymentIntentId = paymentIntentId,
    paymentRecordId = paymentRecordId,
    paymentType = paymentType,
    createdAt = createdAt,
    canceledAt = canceledAt,
    paidAt = paidAt,
    status = status,
    syncedAt = syncedAt
  )

  def makeRichInvoicePayment(
    base: InvoicePayment = makeInvoicePayment(),
    charge: Option[RichCharge] = None,
    paymentIntent: Option[RichPaymentIntent] = None,
  ): RichInvoicePayment = RichInvoicePayment(
    base = base.copy(
      amountPaid = base.amountPaid
        .orElse(charge.map(_.base.amount))
        .orElse(paymentIntent.flatMap(_.charge.map(_.base.amount))),
      paidAt = base.paidAt
        .orElse(charge.map(_.base.created))
        .orElse(paymentIntent.flatMap(_.charge.map(_.base.created)))
    ),
    charge = charge,
    paymentIntent = paymentIntent,
  )

  def makePaymentIntent(
    id: String = s"pi_${genId()}",
    customerId: Option[String] = Some("cus_1"),
    description: Option[String] = None,
    latestChargeId: Option[String] = None,
    syncedAt: Instant = Instant.now()
  ): PaymentIntent = PaymentIntent(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    customerId = customerId,
    amount = 10L,
    currency = "usd",
    description = description,
    latestChargeId = latestChargeId,
    syncedAt = syncedAt
  )

  def makeRichPaymentIntent(
    base: PaymentIntent = makePaymentIntent(),
    charge: Option[RichCharge] = None
  ): RichPaymentIntent = RichPaymentIntent(
    base = base,
    charge = charge
  )

  def makeRevRecTransaction(
    id: String = s"in_${genId()}",
    tpe: RevRecTransaction.Type = RevRecTransaction.Type.Invoice,
    status: RevRecTransaction.Status = RevRecTransaction.Status.Undetermined,
    customerId: Option[String] = Some("cus_1"),
    title: Option[String] = None,
    settlementTotalValue: Option[Long] = None,
    settlementCurrency: Option[String] = None,
    startedAt: Option[Instant] = Some(Instant.now()),
    processedAt: Option[Instant] = None,
    syncedAt: Option[Instant] = Some(Instant.now()),
    batchTimestamp: Instant = Instant.now()
  ): RevRecTransaction = RevRecTransaction(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    tpe = tpe,
    status = status,
    customerId = customerId,
    title = title,
    settlementTotalValue = settlementTotalValue,
    settlementCurrency = settlementCurrency,
    startedAt = startedAt,
    processedAt = processedAt,
    syncedAt = syncedAt,
    batchTimestamp = batchTimestamp
  )

  def makeProcessInvoice(
    transaction: RevRecTransaction,
    invoice: RichInvoice
  ): ProcessInvoice = ProcessInvoice(
    transaction = transaction,
    invoice = invoice
  )

  def makeProcessStandaloneCharge(
    transaction: RevRecTransaction,
    charge: RichCharge
  ): ProcessStandaloneCharge = ProcessStandaloneCharge(
    transaction = transaction,
    charge = charge
  )

  def makeProcessUnbilledInvoiceItem(
    transaction: RevRecTransaction,
    invoiceItem: RichInvoiceItem
  ): ProcessUnbilledInvoiceItem = ProcessUnbilledInvoiceItem(
    transaction = transaction,
    invoiceItem = invoiceItem
  )

  def makeProcessStandalonePaymentIntent(
    transaction: RevRecTransaction,
    paymentIntent: RichPaymentIntent
  ): ProcessStandalonePaymentIntent = ProcessStandalonePaymentIntent(
    transaction = transaction,
    paymentIntent = paymentIntent
  )

  def makeProcessUnbilledUsageSubscriptionItem(
    transaction: RevRecTransaction,
    subscriptionItem: RichSubscriptionItem
  ): ProcessUnbilledUsageSubscriptionItem = ProcessUnbilledUsageSubscriptionItem(
    transaction = transaction,
    subscriptionItem = subscriptionItem
  )

  def makeProcessCreditBalanceTransaction(
    transaction: RevRecTransaction,
    creditBalanceTransaction: RichCreditBalanceTransaction
  ): ProcessCreditBalanceTransaction = ProcessCreditBalanceTransaction(
    transaction = transaction,
    creditBalanceTransaction = creditBalanceTransaction
  )

  def makeProcessCustomerBalanceTransaction(
    transaction: RevRecTransaction,
    customerBalanceTransaction: CustomerBalanceTransaction
  ): ProcessCustomerBalanceTransaction = ProcessCustomerBalanceTransaction(
    transaction = transaction,
    customerBalanceTransaction = customerBalanceTransaction
  )

  def makeSubscription(
    id: String = s"sub_${genId()}",
    customerId: String = "cus_1",
    currency: String = "usd",
    status: String = "active",
    startDate: Instant = Instant.now(),
    discountIds: List[String] = List.empty,
    defaultTaxRateIds: List[String] = List.empty,
    syncedAt: Instant = Instant.now()
  ): Subscription = Subscription(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    customerId = customerId,
    currency = currency,
    status = status,
    startDate = startDate,
    discountIds = discountIds,
    defaultTaxRateIds = defaultTaxRateIds,
    syncedAt = syncedAt
  )

  def makeRichSubscription(
    base: Subscription = makeSubscription(),
    discounts: Seq[RichDiscount] = Seq.empty,
    defaultTaxRates: Seq[TaxRate] = Seq.empty
  ): RichSubscription = RichSubscription(
    base = base,
    discounts = discounts,
    defaultTaxRates = defaultTaxRates
  )

  def makeSubscriptionItem(
    id: String = s"si_${genId()}",
    subscriptionId: String = "sub_1",
    priceId: String = "price_1",
    quantity: Long = 1,
    currentPeriodStart: Instant = Instant.now(),
    currentPeriodEnd: Instant = Instant.now().plus(30, ChronoUnit.DAYS),
    discountIds: List[String] = List.empty,
    taxRateIds: List[String] = List.empty,
    syncedAt: Instant = Instant.now()
  ): SubscriptionItem = SubscriptionItem(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    subscriptionId = subscriptionId,
    priceId = priceId,
    quantity = quantity,
    currentPeriodEnd = currentPeriodEnd,
    currentPeriodStart = currentPeriodStart,
    discountIds = discountIds,
    taxRateIds = taxRateIds,
    syncedAt = syncedAt
  )

  def makePrice(
    id: String = s"price_${genId()}",
    currency: String = "usd",
    productId: String = "prod_1",
    `type`: String = "recurring",
    billingScheme: String = "per_unit",
    unitAmount: Long = 5,
    tiersMode: Option[String] = None,
    recurringInterval: Option[String] = Some("month"),
    recurringIntervalCount: Option[Int] = Some(1),
    recurringMeterId: Option[String] = Some("meter_1"),
    recurringUsageType: Option[String] = Some("metered"),
    syncedAt: Instant = Instant.now()
  ): Price = Price(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    currency = currency,
    productId = productId,
    `type` = `type`,
    billingScheme = billingScheme,
    unitAmount = unitAmount,
    tiersMode = tiersMode,
    recurringInterval = recurringInterval,
    recurringIntervalCount = recurringIntervalCount,
    recurringMeterId = recurringMeterId,
    recurringUsageType = recurringUsageType,
    syncedAt = syncedAt
  )

  def makePriceTier(
    priceId: String = "price_1",
    flatAmount: Option[Long] = None,
    unitAmount: Option[Long] = None,
    upTo: Option[Long] = None,
    syncedAt: Instant = Instant.now()
  ): PriceTier = PriceTier(
    stripeAccountId = "",
    liveMode = false,
    priceId = priceId,
    flatAmount = flatAmount,
    unitAmount = unitAmount,
    upTo = upTo,
    syncedAt = syncedAt
  )

  def makeRichPrice(
    base: Price = makePrice(),
    product: Option[Product] = None,
    tiers: Seq[PriceTier] = Seq.empty
  ): RichPrice = RichPrice(
    base = base,
    product = product,
    tiers = tiers
  )

  def makeMeterEventSummary(
    id: String = s"mes_${genId()}",
    aggregatedValue: Long = 100,
    meterId: String = "meter_1",
    customerId: String = "cus_1",
    startTime: Instant = Instant.now(),
    endTime: Instant = Instant.now().plus(30, ChronoUnit.DAYS),
    syncedAt: Instant = Instant.now()
  ): MeterEventSummary = MeterEventSummary(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    aggregatedValue = aggregatedValue,
    meterId = meterId,
    customerId = customerId,
    startTime = startTime,
    endTime = endTime,
    syncedAt = syncedAt
  )

  def makeRichSubscriptionItem(
    base: SubscriptionItem = makeSubscriptionItem(),
    subscription: RichSubscription = makeRichSubscription(),
    price: Option[RichPrice] = Some(makeRichPrice()),
    meterEventSummaries: Seq[MeterEventSummary] = Seq.empty,
    discounts: Seq[RichDiscount] = Seq.empty,
    taxRates: Seq[TaxRate] = Seq.empty,
    currentPeriodStartExchangeRate: ExchangeRate = ExchangeRate.sameCurrency("usd")
  ): RichSubscriptionItem = RichSubscriptionItem(
    base = base,
    subscription = subscription,
    price = price,
    meterEventSummaries = meterEventSummaries,
    discounts = discounts,
    taxRates = taxRates,
    currentPeriodStartExchangeRate = currentPeriodStartExchangeRate
  )

  def makeRefund(
    id: String = s"re_${genId()}",
    balanceTransactionId: Option[String] = None,
    failureBalanceTransactionId: Option[String] = None,
    amount: Long = 1000,
    currency: String = "usd",
    chargeId: Option[String] = None,
    paymentIntentId: Option[String] = None,
    status: String = "succeeded",
    createdAt: Instant = Instant.now(),
    syncedAt: Instant = Instant.now()
  ): Refund = Refund(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    balanceTransactionId = balanceTransactionId,
    failureBalanceTransactionId = failureBalanceTransactionId,
    amount = amount,
    currency = currency,
    chargeId = chargeId,
    paymentIntentId = paymentIntentId,
    status = status,
    createdAt = createdAt,
    syncedAt = syncedAt
  )

  def makeRichRefund(
    base: Refund = makeRefund(),
    balanceTransaction: Option[BalanceTransaction] = None,
    failureBalanceTransaction: Option[BalanceTransaction] = None,
    belongsToCreditNote: Boolean = false,
  ): RichRefund = RichRefund(
    base = base,
    balanceTransaction = balanceTransaction,
    failureBalanceTransaction = failureBalanceTransaction,
    belongsToCreditNote = belongsToCreditNote
  )

  def makeRichRefund2(
    amount: Long = 1000,
    currency: String = "usd",
    balanceTransactionAmount: Long = -1000,
    balanceTransactionCurrency: String = "usd",
    createdAt: Instant = Instant.now(),
    failureBalanceTransactionAmount: Option[Long] = None,
    failureBalanceTransactionCreatedAt: Option[Instant] = None,
    belongsToCreditNote: Boolean = false,
  ): RichRefund = {
    RichRefund(
      base = makeRefund(amount = amount, currency = currency),
      balanceTransaction = Some(makeBalanceTransaction(amount = balanceTransactionAmount, currency = balanceTransactionCurrency, createdAt = createdAt)),
      failureBalanceTransaction = failureBalanceTransactionAmount.map(amount => makeBalanceTransaction(amount = amount, currency = balanceTransactionCurrency, createdAt = failureBalanceTransactionCreatedAt.getOrElse(createdAt))),
      belongsToCreditNote = belongsToCreditNote
    )
  }

  def makeDispute(
    id: String = s"dp_${genId()}",
    balanceTransactionIds: Seq[String] = Seq.empty,
    amount: Long = 1000,
    currency: String = "usd",
    chargeId: Option[String] = None,
    paymentIntentId: Option[String] = None,
    status: String = "won",
    createdAt: Instant = Instant.now(),
    syncedAt: Instant = Instant.now()
  ): Dispute = Dispute(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    balanceTransactionIds = balanceTransactionIds.toList,
    amount = amount,
    currency = currency,
    chargeId = chargeId,
    paymentIntentId = paymentIntentId,
    status = status,
    createdAt = createdAt,
    syncedAt = syncedAt
  )

  def makeRichDispute2(
    amount: Long = 1000,
    currency: String = "usd",
    balanceTransactionAmount: Long = -1000,
    balanceTransactionFee: Long = 0,
    balanceTransactionCurrency: String = "usd",
    createdAt: Instant = Instant.now(),
    wonBalanceTransactionAmount: Option[Long] = None,
    wonBalanceTransactionCreatedAt: Option[Instant] = None,
  ): RichDispute = {
    RichDispute(
      base = makeDispute(amount = amount, currency = currency),
      balanceTransactions = Seq(
        Some(makeBalanceTransaction(
          amount = balanceTransactionAmount,
          feeAmount = balanceTransactionFee,
          currency = balanceTransactionCurrency,
          createdAt = createdAt
        )),
        wonBalanceTransactionAmount.map(amount => makeBalanceTransaction(amount = amount, currency = balanceTransactionCurrency, createdAt = wonBalanceTransactionCreatedAt.getOrElse(createdAt)))
      ).flatten
    )
  }

  def makeRichDispute(
    base: Dispute = makeDispute(),
    balanceTransactions: Seq[BalanceTransaction] = Seq.empty
  ): RichDispute = RichDispute(
    base = base,
    balanceTransactions = balanceTransactions
  )

  def makeRichCharge2(
    amount: Long = 1000,
    currency: String = "usd",
    balanceTransactionAmount: Long = 1000,
    balanceTransactionFeeAmount: Long = 0,
    balanceTransactionCurrency: String = "usd",
    createdAt: Instant = Instant.now(),
    disputes: Seq[RichDispute] = Seq.empty,
    refunds: Seq[RichRefund] = Seq.empty,
  ): RichCharge = {
    val bt = makeBalanceTransaction(
      id = s"bt_${genId()}",
      amount = balanceTransactionAmount,
      currency = balanceTransactionCurrency,
      feeAmount = balanceTransactionFeeAmount,
      netAmount = balanceTransactionAmount - balanceTransactionFeeAmount,
      createdAt = createdAt,
    )
    val charge = makeCharge(
      id = s"ch_${genId()}",
      amount = amount,
      currency = currency,
      balanceTransactionId = Some(bt.id),
      created = createdAt,
    )

    RichCharge(
      base = charge,
      balanceTransaction = Some(bt),
      disputes = disputes,
      refunds = refunds,
    )
  }

  def makeCharge(
    id: String = s"ch_${genId()}",
    balanceTransactionId: Option[String] = None,
    customerId: Option[String] = Some("cus_1"),
    amount: Long = 1000,
    currency: String = "usd",
    description: Option[String] = None,
    disputed: Boolean = false,
    refunded: Boolean = false,
    amountRefunded: Option[Long] = None,
    paymentIntentId: Option[String] = None,
    created: Instant = Instant.now(),
    status: String = "succeeded",
    syncedAt: Instant = Instant.now()
  ): Charge = Charge(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    balanceTransactionId = balanceTransactionId,
    customerId = customerId,
    amount = amount,
    currency = currency,
    description = description,
    disputed = disputed,
    refunded = refunded,
    amountRefunded = amountRefunded,
    paymentIntentId = paymentIntentId,
    created = created,
    status = status,
    syncedAt = syncedAt
  )

  def makeRichCharge(
    base: Charge = makeCharge(),
    balanceTransaction: Option[BalanceTransaction] = None,
    disputes: Seq[RichDispute] = Seq.empty,
    refunds: Seq[RichRefund] = Seq.empty
  ): RichCharge = RichCharge(
    base = base,
    balanceTransaction = balanceTransaction,
    disputes = disputes,
    refunds = refunds
  )

  def makeBalanceTransaction(
    id: String = s"bt_${genId()}",
    amount: Long = 1000,
    currency: String = "usd",
    description: String = "charge",
    feeAmount: Long = 0,
    netAmount: Long = 1000,
    status: String = "available",
    `type`: String = "charge",
    source: Option[String] = None,
    createdAt: Instant = Instant.now(),
    syncedAt: Instant = Instant.now()
  ): BalanceTransaction = BalanceTransaction(
    stripeAccountId = "",
    liveMode = false,
    id = id,
    amount = amount,
    currency = currency,
    description = description,
    feeAmount = feeAmount,
    netAmount = netAmount,
    status = status,
    `type` = `type`,
    source = source,
    createdAt = createdAt,
    syncedAt = syncedAt
  )
}

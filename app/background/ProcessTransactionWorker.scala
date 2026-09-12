package background

import database.models.{JournalEntry, Transaction}
import database.services.*
import framework.Helpers.await
import framework.Instant
import org.jobrunr.jobs.lambdas.{JobRequest, JobRequestHandler}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{Environment, Logger, Mode, Play}
import process.*
import process.Helpers.formatAmount
import services.ExchangeRateService
import slick.dbio.DBIO

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

case class ProcessTransactionWorkerRequest() extends JobRequest {
  def getJobRequestHandler(): Class[ProcessTransactionWorker] = classOf[ProcessTransactionWorker]
}


object ProcessTransactionWorker {
  case class AmortizationPeriod(
    startedAt: Instant,
    endedAt: Instant,
    amount: Long
  )

  case class JournalEntryPeriod(
    startedAt: Instant,
    settlementAmount: Long,
    presentmentAmount: Long
  )

  def main(args: Array[String]): Unit = {
    val app = GuiceApplicationBuilder(Environment.simple(mode = Mode.Dev)).build()

    Play.start(app)
    val handler = app.injector.instanceOf[ProcessTransactionWorker]
    handler.run(ProcessTransactionWorkerRequest())
  }
}

@Singleton
class ProcessTransactionWorker @Inject() (
  invoiceService: InvoiceService,
  invoiceLineItemService: InvoiceLineItemService,
  invoiceItemService: InvoiceItemService,
  journalEntryService: JournalEntryService,
  transactionService: TransactionService,
  invoicePaymentService: InvoicePaymentService,
  chargeService: ChargeService,
  paymentIntentService: PaymentIntentService,
  refundService: RefundService,
  disputeService: DisputeService,
  balanceTransactionService: BalanceTransactionService,
  subscriptionItemService: SubscriptionItemService,
  exchangeRateService: ExchangeRateService,
  customerBalanceTransactionService: CustomerBalanceTransactionService,
  creditBalanceTransactionService: CreditBalanceTransactionService,
  trackedExceptionService: TrackedExceptionService
)(implicit ec: ExecutionContext) extends BaseJobRequestHandler[ProcessTransactionWorkerRequest](trackedExceptionService) {
  private[this] val logger = Logger(getClass)

  def run2(req: ProcessTransactionWorkerRequest): Unit = {
    val batchTimestamp = Instant.now()
    val unbilledUsageSubscriptionItemSources = await(subscriptionItemService.getAllUnbilledUsageSubscriptionSources())
    logger.info(s"Found ${unbilledUsageSubscriptionItemSources.size} unbilled usage subscription items")
    unbilledUsageSubscriptionItemSources.foreach { src =>
      await(transactionService.createIfNotExist(src.id, Transaction.Type.UnbilledUsageSubscriptionItem, src.stripeAccountId, src.liveMode, src.customerId, batchTimestamp))
    }

    val unbilledInvoiceItemSources = await(invoiceItemService.getAllUnbilledInvoiceItemSources())
    logger.info(s"Found ${unbilledInvoiceItemSources.size} unbilled invoice items")
    unbilledInvoiceItemSources.foreach { src =>
      await(transactionService.createIfNotExist(src.id, Transaction.Type.UnbilledInvoiceItem, src.stripeAccountId, src.liveMode, src.customerId, batchTimestamp))
    }

    val invoiceSources = await(invoiceService.getAllInvoiceSources())
    logger.info(s"Found ${invoiceSources.size} invoices")
    invoiceSources.foreach { src =>
      await(transactionService.createIfNotExist(src.id, Transaction.Type.Invoice, src.stripeAccountId, src.liveMode, src.customerId, batchTimestamp))
    }

    val standaloneChargeSources = await(chargeService.getAllStandaloneChargeSources())
    logger.info(s"Found ${standaloneChargeSources.size} standalone charges")
    standaloneChargeSources.foreach { src =>
      await(transactionService.createIfNotExist(src.id, Transaction.Type.StandaloneCharge, src.stripeAccountId, src.liveMode, src.customerId, batchTimestamp))
    }

    val standalonePaymentIntentSources = await(paymentIntentService.getAllStandalonePaymentIntentSources())
    logger.info(s"Found ${standalonePaymentIntentSources.size} standalone payment intents")
    standalonePaymentIntentSources.foreach { src =>
      await(transactionService.createIfNotExist(src.id, Transaction.Type.StandalonePaymentIntent, src.stripeAccountId, src.liveMode, src.customerId, batchTimestamp))
    }

    val customerBalanceTransactionSources = await(customerBalanceTransactionService.getAllCustomerBalanceTransactionSources())
    logger.info(s"Found ${customerBalanceTransactionSources.size} customer balance transactions")
    customerBalanceTransactionSources.foreach { src =>
      await(transactionService.createIfNotExist(src.id, Transaction.Type.StandaloneCustomerBalanceTransaction, src.stripeAccountId, src.liveMode, src.customerId, batchTimestamp))
    }

    val creditBalanceTransactionSources = await(creditBalanceTransactionService.getAllCreditBalanceTransactionSources())
    logger.info(s"Found ${creditBalanceTransactionSources.size} credit balance transactions")
    creditBalanceTransactionSources.foreach { src =>
      await(transactionService.createIfNotExist(src.id, Transaction.Type.StandaloneCreditBalanceTransaction, src.stripeAccountId, src.liveMode, src.customerId, batchTimestamp))
    }

    deleteOutdateds(batchTimestamp)

    var done = false
    while (!done) {
      val transactions = await(transactionService.getUnprocesseds(batchTimestamp, 100))
      done = transactions.isEmpty
      logger.info(s"Process ${transactions.size} unprocessed transactions")

      transactions.foreach { transaction =>
        val processTransaction = makeProcessTransaction(transaction)

        processTransaction match {
          case Some(processTransaction) => generateJournalEntries(processTransaction)
          case None => await(transactionService.updateProcessedAt(transaction.id, transaction.tpe, Some(Instant.now())))
        }
      }
    }
  }

  def deleteOutdateds(currentBatchTimestamp: Instant): Unit = {
    // Delete outdated transactions
    var done = false
    while (!done) {
      val outdateds = await(transactionService.getOutdateds(currentBatchTimestamp, 100))
      logger.info(s"Deleting ${outdateds.size} outdated transactions")
      done = outdateds.isEmpty

      outdateds.foreach { transaction =>
        await(journalEntryService.deleteByTransactionId(transaction.id))
        await(transactionService.deleteById(transaction.id))
      }
    }

    // Delete stale journal entries
    await(journalEntryService.deleteStaleJournalEntries())
  }

  def makeProcessTransaction(transaction: Transaction): Option[ProcessTransaction] = {
    transaction.tpe match {
      case Transaction.Type.Invoice => makeProcessInvoice(transaction)
      case Transaction.Type.StandaloneCharge => makeProcessStandaloneCharge(transaction)
      case Transaction.Type.StandalonePaymentIntent => makeProcessStandalonePaymentIntent(transaction)
      case Transaction.Type.UnbilledInvoiceItem => makeProcessUnbilledInvoiceItem(transaction)
      case Transaction.Type.UnbilledUsageSubscriptionItem => makeProcessUnbilledUsageSubscriptionItem(transaction)
      case Transaction.Type.StandaloneCustomerBalanceTransaction => makeProcessStandaloneCustomerBalanceTransaction(transaction)
      case Transaction.Type.StandaloneCreditBalanceTransaction => makeProcessStandaloneCreditBalanceTransaction(transaction)
    }
  }

  private def makeProcessStandaloneCustomerBalanceTransaction(transaction: Transaction): Option[ProcessCustomerBalanceTransaction] = {
    val customerBalanceTransactionOpt = await(customerBalanceTransactionService.getById(transaction.id))
    if (customerBalanceTransactionOpt.isEmpty) {
      return None
    }

    Some(ProcessCustomerBalanceTransaction(
      transaction = transaction,
      customerBalanceTransaction = customerBalanceTransactionOpt.get
    ))
  }

  private def makeProcessStandaloneCreditBalanceTransaction(transaction: Transaction): Option[ProcessCreditBalanceTransaction] = {
    val creditBalanceTransactionOpt = await(creditBalanceTransactionService.getRichById(transaction.id))
    if (creditBalanceTransactionOpt.isEmpty) {
      return None
    }

    Some(ProcessCreditBalanceTransaction(
      transaction = transaction,
      creditBalanceTransaction = creditBalanceTransactionOpt.get
    ))
  }

  private def makeProcessUnbilledUsageSubscriptionItem(transaction: Transaction): Option[ProcessTransaction] = {
    val subscriptionItemOpt = await(subscriptionItemService.getRichById(transaction.id))
    if (subscriptionItemOpt.isEmpty) {
      return None
    }

    Some(ProcessUnbilledUsageSubscriptionItem(
      transaction = transaction,
      subscriptionItem = subscriptionItemOpt.get
    ))
  }

  private def makeProcessStandalonePaymentIntent(transaction: Transaction): Option[ProcessTransaction] = {
    val paymentIntentOpt = await(paymentIntentService.getRichById(transaction.id))
    if (paymentIntentOpt.isEmpty) {
      return None
    }

    Some(ProcessStandalonePaymentIntent(
      transaction = transaction,
      paymentIntent = paymentIntentOpt.get
    ))
  }

  private def makeProcessStandaloneCharge(transaction: Transaction): Option[ProcessTransaction] = {
    val chargeOpt = await(chargeService.getRichById(transaction.id))
    if (chargeOpt.isEmpty) {
      return None
    }

    val charge = chargeOpt.get

    Some(ProcessStandaloneCharge(
      transaction = transaction,
      charge = chargeOpt.get,
    ))
  }

  private def makeProcessUnbilledInvoiceItem(transaction: Transaction): Option[ProcessTransaction] = {
    val invoiceItemOpt = await(invoiceItemService.getRichById(transaction.id))
    if (invoiceItemOpt.isEmpty) {
      return None
    }

    val invoiceItem = invoiceItemOpt.get

    Some(ProcessUnbilledInvoiceItem(
      transaction = transaction,
      invoiceItem = invoiceItem.copy(
        createdAtExchangeRate = Some(ProcessUnbilledInvoiceItem.selectInvoiceItemCreatedAtExchangeRate(
          invoiceItem,
          exchangeRateService,
          "usd"
        )),
      )
    ))
  }

  private[this] def makeProcessInvoice(transaction: Transaction): Option[ProcessTransaction] = {
    val invoiceOpt = await(invoiceService.getRichById(transaction.id))
    if (invoiceOpt.isEmpty) {
      logger.info(s"The invoice ${transaction.id} does not exist. Skipping.")
      return None
    }

    val invoice = invoiceOpt.get
    val finalizedAtExchangeRate = ProcessInvoice.selectInvoiceFinalizedAtExchangeRate(
      invoice,
      exchangeRateService,
      "usd"
    )

    Some(ProcessInvoice(
      transaction = transaction,
      invoice = invoice.copy(
        lineItems = invoice.lineItems.map { lineItem =>
          lineItem.copy(
            invoiceItem = lineItem.invoiceItem.map { invoiceItem =>
              invoiceItem.copy(
                createdAtExchangeRate = Some(ProcessUnbilledInvoiceItem.selectInvoiceItemCreatedAtExchangeRate(
                  invoiceItem,
                  exchangeRateService,
                  finalizedAtExchangeRate.exchangeCurrency,
                )),
              )
            }
          )
        },
        finalizedAtExchangeRate = Some(finalizedAtExchangeRate),
      )
    ))
  }

  def generateJournalEntries(
    transaction: ProcessTransaction,
    force: Boolean = false
  ): Unit = {
    if (!force && transaction.transaction.syncedAt.exists(_.toEpochMilli >= transaction.syncedAt.toEpochMilli)) {
      await(transactionService.updateProcessedAt(transaction.transaction.id, transaction.transaction.tpe, Some(Instant.now())))
      return
    }

    logger.info(s"Processing transaction ${transaction.transaction.id} of type ${transaction.transaction.tpe}.")

    val journalEntries = transaction.generateJournalEntries()
    val revenue = Helpers.sumAccountCategory(journalEntries, JournalEntry.AccountCategory.Revenue)
    val contractLiability = Helpers.sumAccountCategory(journalEntries, JournalEntry.AccountCategory.ContractLiability)
    val tcv = transaction.transaction.tpe match {
      case Transaction.Type.Invoice => revenue
      case Transaction.Type.StandalonePaymentIntent => revenue
      case Transaction.Type.StandaloneCharge => revenue
      case Transaction.Type.UnbilledInvoiceItem => revenue
      case Transaction.Type.UnbilledUsageSubscriptionItem => revenue
      case Transaction.Type.StandaloneCustomerBalanceTransaction => contractLiability
      case Transaction.Type.StandaloneCreditBalanceTransaction => contractLiability
    }

    val actions = DBIO.seq(
      journalEntryService.getDeleteByTransactionAction(transaction.transaction.id, transaction.transaction.tpe),
      journalEntryService.getCreateAction(journalEntries),
      transactionService.getUpdateAction(
        transactionId = transaction.transaction.id,
        tpe = transaction.transaction.tpe,
        status = transaction.status,
        startedAt = transaction.startedAt,
        processedAt = Some(Instant.now()),
        journalEntriesGeneratedAt = Some(transaction.syncedAt),
        title = Some(computeTitle(transaction)),
        settlementTotalValue = tcv.map(_.settlement.value),
        settlementCurrency = tcv.map(_.settlement.currency),
      )
    )

    val db = journalEntryService.dbConfigProvider.get.db
    import framework.PostgresProfile.api.*
    await(db.run(actions.transactionally))
  }

  def computeTitle(transaction: ProcessTransaction): String = transaction match {
    case con: ProcessInvoice =>
      s"${formatAmount(con.invoice.base.total, con.invoice.base.currency, false)} ${con.invoice.base.number.getOrElse(con.invoice.base.id)}"
    case con: ProcessStandalonePaymentIntent =>
      s"${formatAmount(con.paymentIntent.base.amount, con.paymentIntent.base.currency, false)} ${con.paymentIntent.base.description.getOrElse(con.paymentIntent.base.id)}"
    case con: ProcessStandaloneCharge =>
      s"${formatAmount(con.charge.base.amount, con.charge.base.currency, false)} ${con.charge.base.description.getOrElse(con.charge.base.id)}"
    case con: ProcessUnbilledInvoiceItem =>
      s"${formatAmount(con.invoiceItem.base.amount, con.invoiceItem.base.currency, false)} ${con.invoiceItem.base.description.getOrElse(con.invoiceItem.base.id)}"
    case con: ProcessUnbilledUsageSubscriptionItem =>
      con.subscriptionItem.price.flatMap(_.product).map(_.name).getOrElse(con.subscriptionItem.base.id)
    case con: ProcessCustomerBalanceTransaction =>
      s"${formatAmount(con.customerBalanceTransaction.amount, con.customerBalanceTransaction.currency, false)} ${con.customerBalanceTransaction.description.getOrElse(con.customerBalanceTransaction.id)} (${con.customerBalanceTransaction.`type`})"
    case con: ProcessCreditBalanceTransaction =>
      val label = con.creditBalanceTransaction.creditGrant
        .map { _.category match {
          case "paid" => "Paid"
          case "promotional" => "Promotional"
        }}
        .getOrElse("Unknown")
      con.creditBalanceTransaction.base.`type` match {
        case Some("credit") => s"${formatAmount(con.creditBalanceTransaction.base.creditAmount.get, con.creditBalanceTransaction.base.creditCurrency.get, false)} $label credit balance"
        case Some("debit") => s"${formatAmount(-con.creditBalanceTransaction.base.debitAmount.get, con.creditBalanceTransaction.base.debitCurrency.get, false)} $label credit balance"
        case _ => throw new RuntimeException(s"Unexpected credit balance transaction type: ${con.creditBalanceTransaction.base.`type`}")
      }
  }
}

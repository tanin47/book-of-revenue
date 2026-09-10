package process

import database.models.{JournalEntry, Transaction}
import database.models.stripe.RichStripeInvoiceItem
import framework.Helpers.await
import framework.Instant
import process.Helpers.{amortize, getAccountingPeriod}
import process.ProcessBillingEvent.{Amount, JournalEntryAmount}
import services.{ExchangeRate, ExchangeRateService}

object ProcessUnbilledInvoiceItem {
  def generateJournalEntries(
    startedAt: Instant,
    endedAt: Instant,
    amount: JournalEntryAmount,
    occurredAt: Instant,
  ): Seq[JournalEntry] = {
    val presentmentPeriods = amortize(amount.presentment.value, startedAt, endedAt)
    val settlementPeriods = amortize(amount.settlement.value, startedAt, endedAt)

    val bookArEntry = makeJournalEntry(
      accountingPeriod = getAccountingPeriod(Instant.min(occurredAt, startedAt)),
      debit = JournalEntry.Account.UnbilledAccountsReceivable,
      credit = JournalEntry.Account.UnbilledDeferredRevenue,
      settlementAmount = settlementPeriods.map(_.amount).sum,
      settlementCurrency = amount.settlement.currency,
      presentmentAmount = presentmentPeriods.map(_.amount).sum,
      presentmentCurrency = amount.presentment.currency,
      occurredAt = occurredAt,
      event = JournalEntry.Event.CreateUnbilledInvoiceItem,
    )

    val recognizedEntries = presentmentPeriods.zip(settlementPeriods).flatMap { case (presentmentPeriod, settlementPeriod) =>
      Seq(
        makeJournalEntry(
          accountingPeriod = settlementPeriod.startedAt,
          debit = JournalEntry.Account.UnbilledDeferredRevenue,
          credit = JournalEntry.Account.Revenue,
          settlementAmount = settlementPeriod.amount,
          settlementCurrency = amount.settlement.currency,
          presentmentAmount = presentmentPeriod.amount,
          presentmentCurrency = amount.presentment.currency,
          occurredAt = Instant.max(settlementPeriod.startedAt, startedAt),
          event = JournalEntry.Event.RecognizeRevenue,
        )
      )
    }

    Seq(bookArEntry) ++ recognizedEntries
  }


  private[this] def makeJournalEntry(
    accountingPeriod: Instant,
    debit: JournalEntry.Account,
    credit: JournalEntry.Account,
    settlementAmount: Long,
    settlementCurrency: String,
    presentmentAmount: Long,
    presentmentCurrency: String,
    occurredAt: Instant,
    event: JournalEntry.Event
  ): JournalEntry = {
    JournalEntry(
      accountingPeriod = accountingPeriod,
      attributionPeriod = None,
      debit = debit,
      credit = credit,
      settlementAmount = settlementAmount,
      settlementCurrency = settlementCurrency,
      presentmentAmount = presentmentAmount,
      presentmentCurrency = presentmentCurrency,
      occurredAt = occurredAt,
      event = event,
      reversedEvent = None,
      principleAccount = JournalEntry.Account.Revenue,
      stripeAccountId = null,
      liveMode = false,
      transactionId = null,
      transactionType = null,
      stripeCustomerId = None,
      stripeInvoiceId = None,
      stripeInvoiceLineItemId = None,
      stripeInvoiceItemId = None,
      stripeChargeId = None,
      stripeBalanceTransactionId = None,
      stripeDisputeId = None,
      stripeRefundId = None,
      stripeCustomerBalanceTransactionId = None,
      stripePaymentIntentId = None,
      stripePaymentRecordId = None,
      stripeSubscriptionId = None,
      stripeSubscriptionItemId = None,
      stripeCreditBalanceTransactionId = None,
      stripeCreditNoteId = None,
      stripeCreditNoteLineItemId = None,
      stripeProductId = None,
      stripePriceId = None,
      createdAt = null
    )
  }

  def selectInvoiceItemCreatedAtExchangeRate(
    invoiceItem: RichStripeInvoiceItem,
    exchangeRateService: ExchangeRateService,
    defaultSettlementCurrency: String,
  ): ExchangeRate = {
    await(exchangeRateService.get(None, invoiceItem.base.currency, defaultSettlementCurrency, invoiceItem.base.createdAt))
  }
}

case class ProcessUnbilledInvoiceItem(
  transaction: Transaction,
  invoiceItem: RichStripeInvoiceItem,
) extends ProcessTransaction {
  lazy val syncedAt: Instant = invoiceItem.base.syncedAt
  lazy val startedAt: Option[Instant] = invoiceItem.base.startedAt
  lazy val status: Transaction.Status = Transaction.Status.Open

  def generateRawJournalEntries(): Seq[JournalEntry] = {
    val startedAt = invoiceItem.base.startedAt.getOrElse(invoiceItem.base.createdAt)
    ProcessUnbilledInvoiceItem.generateJournalEntries(
      startedAt = startedAt,
      endedAt = Instant.max(invoiceItem.base.endedAt.getOrElse(invoiceItem.base.createdAt), startedAt),
      amount = JournalEntryAmount(
        settlement = Amount(
          invoiceItem.createdAtExchangeRate.get.exchange(invoiceItem.totalPrincipleAmount),
          invoiceItem.createdAtExchangeRate.get.exchangeCurrency
        ),
        presentment = Amount(invoiceItem.totalPrincipleAmount, invoiceItem.base.currency),
      ),
      occurredAt = invoiceItem.base.createdAt,
    )
      .map { entry =>
        entry.copy(
          stripeAccountId = transaction.stripeAccountId,
          liveMode = transaction.liveMode,
          transactionId = transaction.id,
          transactionType = transaction.tpe,
          stripeCustomerId = Some(invoiceItem.base.customerId),
          stripeInvoiceItemId = Some(invoiceItem.base.id),
          stripeProductId = invoiceItem.base.productId,
          stripePriceId = invoiceItem.base.priceId,
          createdAt = syncedAt
        )
      }
  }
}

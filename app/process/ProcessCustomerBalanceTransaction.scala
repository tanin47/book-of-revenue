package process

import database.models.{Transaction, JournalEntry}
import database.models.stripe.StripeCustomerBalanceTransaction
import framework.Instant
import process.Helpers.getAccountingPeriod

case class ProcessCustomerBalanceTransaction(
  transaction: Transaction,
  customerBalanceTransaction: StripeCustomerBalanceTransaction,
) extends ProcessTransaction {
  lazy val syncedAt: Instant = customerBalanceTransaction.syncedAt
  lazy val startedAt: Option[Instant] = Some(customerBalanceTransaction.createdAt)
  lazy val status: Transaction.Status = Transaction.Status.Transacted

  def generateRawJournalEntries(): Seq[JournalEntry] = {
    if (customerBalanceTransaction.invoiceId.isDefined || customerBalanceTransaction.creditNoteId.isDefined) {
      // Handled by ProcessInvoiceItem
      return Seq.empty
    }

    Seq(JournalEntry(
      accountingPeriod = getAccountingPeriod(customerBalanceTransaction.createdAt),
      attributionPeriod = None,
      debit = JournalEntry.Account.CustomerBalance,
      credit = JournalEntry.Account.CustomerBalanceAdjustment,
      settlementAmount = customerBalanceTransaction.amount,
      settlementCurrency = customerBalanceTransaction.currency,
      presentmentAmount = customerBalanceTransaction.amount,
      presentmentCurrency = customerBalanceTransaction.currency,
      occurredAt = customerBalanceTransaction.createdAt,
      event = JournalEntry.Event.AdjustCustomerBalanceManually,
      reversedEvent = None,
      principleAccount = JournalEntry.Account.CustomerBalance,
      stripeAccountId = transaction.stripeAccountId,
      liveMode = transaction.liveMode,
      transactionId = transaction.id,
      transactionType = transaction.tpe,
      stripeCustomerId = Some(customerBalanceTransaction.customerId),
      stripeInvoiceId = None,
      stripeInvoiceLineItemId = None,
      stripeInvoiceItemId = None,
      stripeChargeId = None,
      stripeBalanceTransactionId = None,
      stripeDisputeId = None,
      stripeRefundId = None,
      stripeCustomerBalanceTransactionId = Some(customerBalanceTransaction.id),
      stripePaymentIntentId = None,
      stripePaymentRecordId = None,
      stripeSubscriptionId = None,
      stripeSubscriptionItemId = None,
      stripeCreditBalanceTransactionId = None,
      stripeCreditNoteId = None,
      stripeCreditNoteLineItemId = None,
      stripeProductId = None,
      stripePriceId = None,
      createdAt = syncedAt
    ))
  }
}

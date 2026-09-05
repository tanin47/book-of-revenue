package process

import database.models.{RevRecTransaction, CustomerBalanceTransaction, JournalEntry}
import framework.Instant
import process.Helpers.getAccountingPeriod

case class ProcessCustomerBalanceTransaction(
  transaction: RevRecTransaction,
  customerBalanceTransaction: CustomerBalanceTransaction,
) extends ProcessRevRecTransaction {
  lazy val syncedAt: Instant = customerBalanceTransaction.syncedAt
  lazy val startedAt: Option[Instant] = Some(customerBalanceTransaction.createdAt)
  lazy val status: RevRecTransaction.Status = RevRecTransaction.Status.Transacted

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
      revRecTransactionId = transaction.id,
      revRecTransactionType = transaction.tpe,
      customerId = Some(customerBalanceTransaction.customerId),
      invoiceId = None,
      invoiceLineItemId = None,
      invoiceItemId = None,
      chargeId = None,
      balanceTransactionId = None,
      disputeId = None,
      refundId = None,
      customerBalanceTransactionId = Some(customerBalanceTransaction.id),
      paymentIntentId = None,
      paymentRecordId = None,
      subscriptionId = None,
      subscriptionItemId = None,
      creditBalanceTransactionId = None,
      creditNoteId = None,
      creditNoteLineItemId = None,
      productId = None,
      priceId = None,
      createdAt = syncedAt
    ))
  }
}

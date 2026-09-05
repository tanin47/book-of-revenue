package process

import database.models.{RevRecTransaction, JournalEntry, RichCreditBalanceTransaction, RichInvoiceItem}
import framework.Instant
import process.Helpers.{amortize, getAccountingPeriod}
import process.ProcessBillingEvent.{Amount, JournalEntryAmount}

case class ProcessCreditBalanceTransaction(
  transaction: RevRecTransaction,
  creditBalanceTransaction: RichCreditBalanceTransaction
) extends ProcessRevRecTransaction {
  lazy val syncedAt: Instant = creditBalanceTransaction.base.syncedAt
  lazy val startedAt: Option[Instant] = Some(creditBalanceTransaction.base.effectiveAt)
  lazy val status: RevRecTransaction.Status = RevRecTransaction.Status.Transacted

  def generateRawJournalEntries(): Seq[JournalEntry] = {
    if (
      // Handled by ProcessInvoiceItem
      creditBalanceTransaction.base.creditInvoiceVoidedInvoiceId.isDefined ||
      creditBalanceTransaction.base.debitCreditsAppliedInvoiceId.isDefined
    )   {
      return Seq.empty
    }

    val debit = creditBalanceTransaction.creditGrant.get.category match {
      case "paid" => JournalEntry.Account.PaidCreditGrantContraAsset
      case "promotional" => JournalEntry.Account.PromotionalCreditGrantExpense
    }
    val credit = creditBalanceTransaction.creditGrant.get.category match {
      case "paid" => JournalEntry.Account.PaidCreditGrants
      case "promotional" => JournalEntry.Account.PromotionalCreditGrants
    }

    val amount = creditBalanceTransaction.base.`type` match {
      case Some("debit") =>  -Amount(creditBalanceTransaction.base.debitAmount.get, creditBalanceTransaction.base.debitCurrency.get)
      case Some("credit") => Amount(creditBalanceTransaction.base.creditAmount.get, creditBalanceTransaction.base.creditCurrency.get)
      case _ => throw new RuntimeException(s"CreditBalanceTransaction type is invalid: ${creditBalanceTransaction.base.`type`}")
    }

    Seq(JournalEntry(
      accountingPeriod = getAccountingPeriod(creditBalanceTransaction.base.effectiveAt),
      attributionPeriod = None,
      debit = debit,
      credit = credit,
      settlementAmount = amount.value,
      settlementCurrency = amount.currency,
      presentmentAmount = amount.value,
      presentmentCurrency = amount.currency,
      occurredAt = creditBalanceTransaction.base.effectiveAt,
      event = if (creditBalanceTransaction.base.debitType.contains("credits_applied")) {
        JournalEntry.Event.VoidInvoice
      } else if (creditBalanceTransaction.base.debitType.contains("credits_expired")) {
        JournalEntry.Event.ExpireCreditGrant
      } else if (creditBalanceTransaction.base.debitType.contains("credits_voided")) {
        JournalEntry.Event.VoidCreditGrant
      } else if (creditBalanceTransaction.base.creditType.contains("credits_granted")) {
        JournalEntry.Event.CreateCreditGrant
      } else {
        throw new Exception()
      },
      reversedEvent = None,
      principleAccount = credit,
      stripeAccountId = transaction.stripeAccountId,
      liveMode = transaction.liveMode,
      revRecTransactionId = transaction.id,
      revRecTransactionType = transaction.tpe,
      customerId = creditBalanceTransaction.creditGrant.map(_.customer),
      invoiceId = creditBalanceTransaction.base.creditInvoiceVoidedInvoiceId,
      invoiceLineItemId = None,
      invoiceItemId = None,
      chargeId = None,
      balanceTransactionId = None,
      disputeId = None,
      refundId = None,
      customerBalanceTransactionId = None,
      paymentIntentId = None,
      paymentRecordId = None,
      subscriptionId = None,
      subscriptionItemId = None,
      creditBalanceTransactionId = Some(creditBalanceTransaction.base.id),
      creditNoteId = None,
      creditNoteLineItemId = None,
      productId = None,
      priceId = None,
      createdAt = syncedAt
    ))
  }
}

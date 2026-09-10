package process

import database.models.{Transaction, JournalEntry}
import database.models.stripe.{RichStripeCreditBalanceTransaction, RichStripeInvoiceItem}
import framework.Instant
import process.Helpers.{amortize, getAccountingPeriod}
import process.ProcessBillingEvent.{Amount, JournalEntryAmount}

case class ProcessCreditBalanceTransaction(
  transaction: Transaction,
  creditBalanceTransaction: RichStripeCreditBalanceTransaction
) extends ProcessTransaction {
  lazy val syncedAt: Instant = creditBalanceTransaction.base.syncedAt
  lazy val startedAt: Option[Instant] = Some(creditBalanceTransaction.base.effectiveAt)
  lazy val status: Transaction.Status = Transaction.Status.Transacted

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
      transactionId = transaction.id,
      transactionType = transaction.tpe,
      stripeCustomerId = creditBalanceTransaction.creditGrant.map(_.customer),
      stripeInvoiceId = creditBalanceTransaction.base.creditInvoiceVoidedInvoiceId,
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
      stripeCreditBalanceTransactionId = Some(creditBalanceTransaction.base.id),
      stripeCreditNoteId = None,
      stripeCreditNoteLineItemId = None,
      stripeProductId = None,
      stripePriceId = None,
      createdAt = syncedAt
    ))
  }
}

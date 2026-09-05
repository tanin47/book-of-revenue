package process

import database.models.*
import framework.Instant

case class ProcessStandalonePaymentIntent(
  transaction: RevRecTransaction,
  paymentIntent: RichPaymentIntent,
) extends ProcessRevRecTransaction {
  lazy val processStandaloneCharge: ProcessStandaloneCharge = ProcessStandaloneCharge(transaction, paymentIntent.charge.get)
  lazy val syncedAt: Instant = paymentIntent.syncedAt
  lazy val startedAt: Option[Instant] = processStandaloneCharge.startedAt
  lazy val status: RevRecTransaction.Status = processStandaloneCharge.status

  def generateRawJournalEntries(): Seq[JournalEntry] = {
    if (paymentIntent.charge.isEmpty) {
      return Seq.empty
    }

    ProcessStandaloneCharge(transaction, paymentIntent.charge.get)
      .generateRawJournalEntries()
      .map { entry =>
        entry.copy(
          stripeAccountId = transaction.stripeAccountId,
          liveMode = transaction.liveMode,
          revRecTransactionId = transaction.id,
          revRecTransactionType = transaction.tpe,
          customerId = paymentIntent.base.customerId,
          paymentIntentId = Some(paymentIntent.base.id),
          createdAt = syncedAt
        )
      }
  }
}

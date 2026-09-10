package process

import database.models.*
import database.models.stripe.*
import framework.Instant

case class ProcessStandalonePaymentIntent(
  transaction: Transaction,
  paymentIntent: RichStripePaymentIntent,
) extends ProcessTransaction {
  lazy val processStandaloneCharge: ProcessStandaloneCharge = ProcessStandaloneCharge(transaction, paymentIntent.charge.get)
  lazy val syncedAt: Instant = paymentIntent.syncedAt
  lazy val startedAt: Option[Instant] = processStandaloneCharge.startedAt
  lazy val status: Transaction.Status = processStandaloneCharge.status

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
          transactionId = transaction.id,
          transactionType = transaction.tpe,
          stripeCustomerId = paymentIntent.base.customerId,
          stripePaymentIntentId = Some(paymentIntent.base.id),
          createdAt = syncedAt
        )
      }
  }
}

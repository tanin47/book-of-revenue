package process

import database.models.*
import database.models.metronome.RichMetronomeDraftInvoice
import database.models.stripe.*
import framework.Instant
import process.Helpers.{amortize, generatePeriods}
import process.ProcessBillingEvent.{Amount, JournalEntryAmount}
import services.ExchangeRate

case class ProcessMetronomeDraftInvoice(
  transaction: Transaction,
  invoice: RichMetronomeDraftInvoice,
) extends ProcessTransaction {
  lazy val syncedAt: Instant = invoice.base.updatedAt.get
  lazy val startedAt: Option[Instant] = invoice.base.startTimestamp
  lazy val status: Transaction.Status = Transaction.Status.Draft

  def generateRawJournalEntries(): Seq[JournalEntry] = {
    val breakdownLineItemsByLineItemId = invoice.breakdownInvoice.toList.flatMap(_.lineItems).groupBy(_.lineItemId.get)
    invoice.lineItems
      .filter { lineItem => !lineItem.isPrepaidCommit }
      .filter { lineItem => breakdownLineItemsByLineItemId.getOrElse(lineItem.id, Seq.empty).forall(!_.isAppliedCredit)  }
      .flatMap { lineItem =>
        val breakdowns = breakdownLineItemsByLineItemId.getOrElse(lineItem.id, Seq.empty)

        val minTime = breakdowns.flatMap(_.breakdownStartTimestamp).min
        val maxTime = breakdowns.flatMap(_.breakdownEndTimestamp).max
        val presentmentPeriods = Helpers.generatePeriods(minTime, maxTime).map { period =>
          period.copy(
            amount = breakdowns
              .filter { i =>
                period.startedAt.compareTo(i.breakdownStartTimestamp.get) <= 0 &&
                  i.breakdownEndTimestamp.get.compareTo(period.endedAt) <= 0
              }
              .map(_.total.get.toLong)
              .sum
          )
        }

        presentmentPeriods
          .map { period =>
            JournalEntry(
              accountingPeriod = period.startedAt,
              attributionPeriod = None,
              debit = JournalEntry.Account.UnbilledAccountsReceivable,
              credit = JournalEntry.Account.Revenue,
              settlementAmount = period.amount,
              settlementCurrency = lineItem.currency,
              presentmentAmount = period.amount,
              presentmentCurrency = lineItem.currency,
              occurredAt = period.endedAt,
              event = JournalEntry.Event.RecognizeRevenue,
              reversedEvent = None,
              principleAccount = JournalEntry.Account.Revenue,
              stripeAccountId = transaction.stripeAccountId,
              liveMode = transaction.liveMode,
              transactionId = transaction.id,
              transactionType = transaction.tpe,
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
              createdAt = syncedAt
            )
          }
      }

  }
}

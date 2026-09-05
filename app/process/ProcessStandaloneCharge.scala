package process

import database.models.*
import framework.Instant
import process.Helpers.getAccountingPeriod
import process.ProcessBillingEvent.{Amount, JournalEntryAmount, MarkPaidBillingEvent, MoneyMovementBillingEvent}

object ProcessStandaloneCharge {
  case class Contra(
    presentmentAmount: Long,
    presentmentCurrency: String,
    occurredAt: Instant,
    chargeId: String,
    paymentIntentId: Option[String],
    refundId: Option[String],
    disputeId: Option[String],
    balanceTransaction: BalanceTransaction,
  )

  case class ContraState(
    contraPresentmentAmount: Long,
    contraSettlementAmount: Long,
    fxLoss: Long,
    recoverable: Long,
    entries: Seq[JournalEntry]
  )
}

case class ProcessStandaloneCharge(
  transaction: RevRecTransaction,
  charge: RichCharge,
) extends ProcessRevRecTransaction {
  lazy val syncedAt: Instant = charge.syncedAt
  lazy val startedAt: Option[Instant] = charge.balanceTransaction.map(_.createdAt)
  lazy val status: RevRecTransaction.Status = if (charge.balanceTransaction.isDefined) {
    RevRecTransaction.Status.Paid
  } else {
    RevRecTransaction.Status.Unpaid
  }

  def generateRawJournalEntries(): Seq[JournalEntry] = {
    if (charge.balanceTransaction.isEmpty) { return Seq.empty }

    val entriesAtBillingTime = bookAr() ++ bookRevenue()
    (
      entriesAtBillingTime ++ Seq(
        bookBillingEvents(entriesAtBillingTime),
        bookFees(),
      ).flatten
    )
      .map { entry => entry.copy(
        principleAccount = JournalEntry.Account.Revenue,
        stripeAccountId = transaction.stripeAccountId,
        liveMode = transaction.liveMode,
        revRecTransactionId = transaction.id,
        revRecTransactionType = transaction.tpe,
        customerId = charge.base.customerId,
        chargeId = Some(charge.base.id),
        paymentIntentId = charge.base.paymentIntentId,
        createdAt = syncedAt
      ) }
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
    event: JournalEntry.Event,
    invoiceId: Option[String] = None,
    invoiceLineItemId: Option[String] = None,
    invoiceItemId: Option[String] = None,
    balanceTransactionId: Option[String] = None,
    disputeId: Option[String] = None,
    refundId: Option[String] = None,
    customerBalanceTransactionId: Option[String] = None,
    paymentRecordId: Option[String] = None
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
      stripeAccountId = transaction.stripeAccountId,
      liveMode = transaction.liveMode,
      revRecTransactionId = transaction.id,
      revRecTransactionType = transaction.tpe,
      customerId = charge.base.customerId,
      invoiceId = invoiceId,
      invoiceLineItemId = invoiceLineItemId,
      invoiceItemId = invoiceItemId,
      chargeId = Some(charge.base.id),
      balanceTransactionId = balanceTransactionId,
      disputeId = disputeId,
      refundId = refundId,
      customerBalanceTransactionId = customerBalanceTransactionId,
      paymentIntentId = charge.base.paymentIntentId,
      paymentRecordId = paymentRecordId,
      subscriptionId = None,
      subscriptionItemId = None,
      creditBalanceTransactionId = None,
      creditNoteId = None,
      creditNoteLineItemId = None,
      productId = None,
      priceId = None,
      createdAt = syncedAt
    )
  }

  private[this] def bookFees(): Seq[JournalEntry] = {
    val fromCharge = charge.balanceTransaction.filter(_.feeAmount != 0).map { bt =>
      makeJournalEntry(
        accountingPeriod = getAccountingPeriod(bt.createdAt),
        debit = JournalEntry.Account.Fees,
        credit = JournalEntry.Account.Cash,
        settlementAmount = bt.feeAmount,
        settlementCurrency = bt.currency,
        presentmentAmount = 0L,
        presentmentCurrency = charge.base.currency,
        occurredAt = bt.createdAt,
        event = JournalEntry.Event.PayFee,
        balanceTransactionId = Some(bt.id),
      )
    }

    val fromRefunds = charge.refunds.flatMap { r =>
      r.balanceTransaction.filter(_.feeAmount != 0).map { bt =>
        makeJournalEntry(
          accountingPeriod = getAccountingPeriod(bt.createdAt),
          debit = JournalEntry.Account.Fees,
          credit = JournalEntry.Account.Cash,
          settlementAmount = bt.feeAmount,
          settlementCurrency = bt.currency,
          presentmentAmount = 0L,
          presentmentCurrency = r.base.currency,
          occurredAt = bt.createdAt,
          event = JournalEntry.Event.PayFee,
          balanceTransactionId = Some(bt.id),
          refundId = Some(r.base.id),
        )
      }
    }

    val fromDisputes = charge.disputes.flatMap { d =>
      d.balanceTransactions.filter(_.feeAmount != 0).map { bt =>
        makeJournalEntry(
          accountingPeriod = getAccountingPeriod(bt.createdAt),
          debit = JournalEntry.Account.Fees,
          credit = JournalEntry.Account.Cash,
          settlementAmount = bt.feeAmount,
          settlementCurrency = bt.currency,
          presentmentAmount = 0L,
          presentmentCurrency = d.base.currency,
          occurredAt = bt.createdAt,
          event = JournalEntry.Event.PayFee,
          balanceTransactionId = Some(bt.id),
          disputeId = Some(d.base.id),
        )
      }
    }

    fromCharge.toList ++ fromRefunds.toList ++ fromDisputes.toList
  }

  private[this] def bookBillingEvents(entries: Seq[JournalEntry]): Seq[JournalEntry] = {
    val paymentEvents = Seq(
      MoneyMovementBillingEvent(
        amount = JournalEntryAmount(
          settlement = Amount(charge.balanceTransaction.get.amount, charge.balanceTransaction.get.currency),
          presentment = Amount(charge.base.amount, charge.base.currency),
        ),
        amountBreakdown = None,
        journalEntryEvent = JournalEntry.Event.CreateCharge,
        assetAccount = JournalEntry.Account.Cash,
        contraAccount = None,
        occurredAt = charge.balanceTransaction.get.createdAt,
        payAndMoveMoney = true,
        balanceTransactionId = Some(charge.balanceTransaction.get.id),
      ),
      MarkPaidBillingEvent(
        occurredAt = charge.balanceTransaction.get.createdAt,
        settlementCurrency = charge.balanceTransaction.get.currency,
        presentmentCurrency = charge.base.currency,
      )
    )

    val refundEvents = charge.refunds.flatMap { refund =>
      Seq(
        refund.balanceTransaction.map { bt =>
          MoneyMovementBillingEvent(
            amount = JournalEntryAmount(
              settlement = Amount(bt.amount, bt.currency),
              presentment = Amount(-refund.base.amount, refund.base.currency),
            ),
            amountBreakdown = None,
            journalEntryEvent = JournalEntry.Event.RefundCharge,
            assetAccount = JournalEntry.Account.Cash,
            contraAccount = Some(JournalEntry.Account.Refunds),
            occurredAt = bt.createdAt,
            refundId = Some(refund.base.id),
            balanceTransactionId = Some(bt.id),
          )
        },
        refund.failureBalanceTransaction.map { bt =>
          MoneyMovementBillingEvent(
            amount = JournalEntryAmount(
              settlement = Amount(bt.amount, bt.currency),
              presentment = Amount(refund.base.amount, refund.base.currency),
            ),
            amountBreakdown = None,
            journalEntryEvent = JournalEntry.Event.FailRefund,
            assetAccount = JournalEntry.Account.Cash,
            contraAccount = Some(JournalEntry.Account.Refunds),
            occurredAt = bt.createdAt,
            refundId = Some(refund.base.id),
            balanceTransactionId = Some(bt.id),
          )
        }
      ).flatten
    }

    val disputeEvents = charge.disputes.flatMap { dispute =>
      dispute.balanceTransactions.map { bt =>
        MoneyMovementBillingEvent(
          amount = JournalEntryAmount(
            settlement = Amount(bt.amount, bt.currency),
            presentment = Amount(if (bt.amount < 0) -dispute.base.amount else dispute.base.amount, dispute.base.currency)
          ),
          amountBreakdown = None,
          journalEntryEvent = if (bt.amount < 0) {
            JournalEntry.Event.DisputeCharge
          } else {
            JournalEntry.Event.WinDispute
          },
          assetAccount = JournalEntry.Account.Cash,
          contraAccount = Some(JournalEntry.Account.Disputes),
          occurredAt = bt.createdAt,
          disputeId = Some(dispute.base.id),
          balanceTransactionId = Some(bt.id),
        )
      }
    }

    val billedAmount = JournalEntryAmount(
      settlement = Amount(charge.balanceTransaction.get.amount, charge.balanceTransaction.get.currency),
      presentment = Amount(charge.base.amount, charge.base.currency),
    )
    val events = ProcessBillingEvent.buildJournalEntryEvents(
      billedAmount = billedAmount,
      events = (paymentEvents ++ refundEvents ++ disputeEvents).sorted
    )

    ProcessBillingEvent.buildJournalEntries(
      settlementCurrency = charge.balanceTransaction.get.currency,
      presentmentCurrency = charge.base.currency,
      invoicingEntries = entries,
      events = events,
    )
  }

  private[this] def bookAr(): Seq[JournalEntry] = {
    Seq(makeJournalEntry(
      accountingPeriod = getAccountingPeriod(charge.base.created),
      debit = JournalEntry.Account.AccountsReceivable,
      credit = JournalEntry.Account.DeferredRevenue,
      settlementAmount = charge.balanceTransaction.get.amount,
      settlementCurrency = charge.balanceTransaction.get.currency,
      presentmentAmount = charge.base.amount,
      presentmentCurrency = charge.base.currency,
      occurredAt = charge.base.created,
      event = JournalEntry.Event.CreateCharge,
      balanceTransactionId = charge.base.balanceTransactionId,
    ))
  }

  private[this] def bookRevenue(): Seq[JournalEntry] = {
    Seq(makeJournalEntry(
      accountingPeriod = getAccountingPeriod(charge.base.created),
      debit = JournalEntry.Account.DeferredRevenue,
      credit = JournalEntry.Account.Revenue,
      settlementAmount = charge.balanceTransaction.get.amount,
      settlementCurrency = charge.balanceTransaction.get.currency,
      presentmentAmount = charge.base.amount,
      presentmentCurrency = charge.base.currency,
      occurredAt = charge.base.created,
      event = JournalEntry.Event.RecognizeRevenue,
      balanceTransactionId = charge.base.balanceTransactionId,
    ))
  }
}

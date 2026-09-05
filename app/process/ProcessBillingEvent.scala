package process

import background.ProcessTransactionWorker.JournalEntryPeriod
import database.models.JournalEntry
import database.models.JournalEntry.AccountCategory
import framework.Helpers.printEntries
import framework.Instant
import play.api.Logger
import process.Helpers.*

object ProcessBillingEvent {
  private[this] val logger = Logger(getClass)

  case class BilledAmount(
    recognizedRevenuePeriods: Seq[JournalEntryPeriod],
    taxLiability: DualAmount
  )

  case class Amount(value: Long, currency: String) {
    lazy val isZero: Boolean = value == 0

    def +(that: Amount): Amount = if (currency == that.currency) {
      Amount(value + that.value, currency)
    } else {
      throw new UnsupportedOperationException("Amount.+ for different currencies")
    }

    def -(that: Amount): Amount = if (currency == that.currency) {
      Amount(value - that.value, currency)
    } else {
      throw new UnsupportedOperationException("Amount.+ for different currencies")
    }

    def unary_- : Amount = Amount(-value, currency)
    def min(that: Amount): Amount = if (currency == that.currency) {
      Amount(Math.min(value, that.value), currency)
    } else {
      throw new UnsupportedOperationException("Amount.min for different currencies")
    }
    def max(that: Amount): Amount = if (currency == that.currency) {
      Amount(Math.max(value, that.value), currency)
    } else {
      throw new UnsupportedOperationException("Amount.max for different currencies")
    }
  }

  case class DualAmount(
    settlement: Long,
    presentment: Long
  ) {
    lazy val isZero: Boolean = settlement == 0 && presentment == 0

    def +(that: DualAmount): DualAmount = DualAmount(settlement + that.settlement, presentment + that.presentment)

    def -(that: DualAmount): DualAmount = DualAmount(settlement - that.settlement, presentment - that.presentment)

    def unary_- : DualAmount = DualAmount(-settlement, -presentment)

    def toJournalEntryAmount(settlementCurrency: String, presentmentCurrency: String): JournalEntryAmount = {
      JournalEntryAmount(Amount(settlement, settlementCurrency), Amount(presentment, presentmentCurrency))
    }
  }

  object JournalEntryAmount {
    def empty(settlementCurrency: String, presentmentCurrency: String): JournalEntryAmount = JournalEntryAmount(Amount(0L, settlementCurrency), Amount(0L, presentmentCurrency))
  }

  case class JournalEntryAmount(
    settlement: Amount,
    presentment: Amount
  ) {
    lazy val isZero: Boolean = settlement.isZero && presentment.isZero

    def +(that: JournalEntryAmount): JournalEntryAmount = JournalEntryAmount(settlement + that.settlement, presentment + that.presentment)
    def -(that: JournalEntryAmount): JournalEntryAmount = JournalEntryAmount(settlement - that.settlement, presentment - that.presentment)
    def unary_- : JournalEntryAmount = JournalEntryAmount(-settlement, -presentment)

    def min(that: JournalEntryAmount): JournalEntryAmount = if (settlement.currency == that.settlement.currency) {
      if (settlement.value < that.settlement.value) {
        this
      } else {
        that
      }
    } else {
      throw new UnsupportedOperationException("Amount.min for different currencies")
    }

    def toDualAmount(): DualAmount = DualAmount(settlement.value, presentment.value)
  }

  def sum(
    amounts: Seq[JournalEntryAmount],
    settlementCurrency: String,
    presentmentCurrency: String,
  ): JournalEntryAmount = {
    amounts.foldLeft(JournalEntryAmount(Amount(0L, settlementCurrency), Amount(0L, presentmentCurrency)))(_ + _)
  }

  case class LineItem(
    amount: JournalEntryAmount,
    startedAt: Instant,
    endedAt: Instant
  )

  case class JournalEntryReference(
    chargeId: Option[String],
    paymentIntentId: Option[String],
    disputeId: Option[String],
    refundId: Option[String],
    balanceTransactionId: Option[String],
    paymentRecordId: Option[String],
    creditNoteId: Option[String],
    creditNoteLineItemId: Option[String]
  )

  sealed abstract class BillingEvent extends Ordered[BillingEvent] {
    def occurredAt: Instant
    def settlementCurrency: String
    def presentmentCurrency: String
    def rank: Int

    override def compare(that: BillingEvent): Int = {
      val result = occurredAt.compareTo(that.occurredAt)

      if (result != 0) {
        result
      } else {
        rank.compareTo(that.rank)
      }
    }
  }
  case class MarkUncollectibleBillingEvent(
    occurredAt: Instant,
    principleAccount: JournalEntry.Account,
    settlementCurrency: String,
    presentmentCurrency: String
  ) extends BillingEvent {
    val rank = 10
  }
  case class VoidBillingEvent(
    occurredAt: Instant,
    principleAccount: JournalEntry.Account,
    settlementCurrency: String,
    presentmentCurrency: String,
  ) extends BillingEvent {
    val rank = 10
  }
  case class MarkPaidBillingEvent(
    occurredAt: Instant,
    settlementCurrency: String,
    presentmentCurrency: String,
  ) extends BillingEvent {
    val rank = 5
  }
  case class AmountBreakdown(
    revenue: DualAmount,
    tax: DualAmount,
  ) {
    def unary_- : AmountBreakdown = AmountBreakdown(-revenue, -tax)
  }
  case class PayInvoiceBillingEvent(
    amount: JournalEntryAmount,
    occurredAt: Instant,
    chargeId: Option[String] = None,
    paymentIntentId: Option[String] = None,
    paymentRecordId: Option[String] = None,
    invoiceLineItemId: Option[String] = None,
  ) extends BillingEvent {
    lazy val settlementCurrency: String = amount.settlement.currency
    lazy val presentmentCurrency: String = amount.presentment.currency

    val rank = 4
  }
  case class MoneyMovementBillingEvent(
    amount: JournalEntryAmount,
    amountBreakdown: Option[AmountBreakdown],
    journalEntryEvent: JournalEntry.Event,
    assetAccount: JournalEntry.Account,
    contraAccount: Option[JournalEntry.Account],
    occurredAt: Instant,
    payAndMoveMoney: Boolean = false,
    chargeId: Option[String] = None,
    paymentIntentId: Option[String] = None,
    disputeId: Option[String] = None,
    refundId: Option[String] = None,
    balanceTransactionId: Option[String] = None,
    paymentRecordId: Option[String] = None,
    creditNoteId: Option[String] = None,
    creditNoteLineItemId: Option[String] = None,
    invoiceLineItemId: Option[String] = None
  ) extends BillingEvent {
    lazy val settlementCurrency: String = amount.settlement.currency
    lazy val presentmentCurrency: String = amount.presentment.currency
    val rank: Int = if (contraAccount.isDefined) {
      6
    } else {
      4
    }
  }
  case class PrepaidCreditNoteIssuedBillingEvent(
    totalAmount: JournalEntryAmount,
    principleAccount: JournalEntry.Account,
    amountBreakdown: Option[AmountBreakdown],
    occurredAt: Instant,
    creditNoteId: String,
    creditNoteLineItemId: Option[String],
    invoiceLineItemId: Option[String]
  ) extends BillingEvent {
    lazy val settlementCurrency: String = totalAmount.settlement.currency
    lazy val presentmentCurrency: String = totalAmount.presentment.currency

    val rank = 3
  }
  case class PrepaidCreditNoteVoidedBillingEvent(
    occurredAt: Instant,
    principleAccount: JournalEntry.Account,
    settlementCurrency: String,
    presentmentCurrency: String,
    creditNoteId: String,
    creditNoteLineItemId: Option[String],
    invoiceLineItemId: Option[String]
  ) extends BillingEvent {
    val rank = 3
  }

  sealed abstract class JournalEntryEvent {
    def journalEntryEvent: JournalEntry.Event
    def occurredAt: Instant
    def reference: JournalEntryReference
    def isZero: Boolean

    def toJournalEntry(
      debit: JournalEntry.Account,
      credit: JournalEntry.Account,
      amount: JournalEntryAmount,
    ): JournalEntry = {
      JournalEntry(
        accountingPeriod = getAccountingPeriod(occurredAt),
        attributionPeriod = None,
        debit = debit,
        credit = credit,
        settlementAmount = amount.settlement.value,
        settlementCurrency = amount.settlement.currency,
        presentmentAmount = amount.presentment.value,
        presentmentCurrency = amount.presentment.currency,
        occurredAt = occurredAt,
        stripeAccountId = null,
        liveMode = false,
        revRecTransactionId = null,
        revRecTransactionType = null,
        customerId = None,
        event = journalEntryEvent,
        reversedEvent = None,
        principleAccount = null,
        invoiceId = None,
        invoiceLineItemId = None,
        invoiceItemId = None,
        chargeId = reference.chargeId,
        balanceTransactionId = reference.balanceTransactionId,
        disputeId = reference.disputeId,
        refundId = reference.refundId,
        customerBalanceTransactionId = None,
        paymentIntentId = reference.paymentIntentId,
        paymentRecordId = reference.paymentRecordId,
        subscriptionId = None,
        subscriptionItemId = None,
        creditBalanceTransactionId = None,
        creditNoteId = reference.creditNoteId,
        creditNoteLineItemId = reference.creditNoteLineItemId,
        productId = None,
        priceId = None,
        createdAt = null
      )
    }
  }
  sealed abstract class AmountBasedJournalEntryEvent extends JournalEntryEvent {
    def amount: JournalEntryAmount
    lazy val isZero: Boolean = amount.isZero
  }
  case class PayInvoiceJournalEntryEvent(
    amount: JournalEntryAmount,
    journalEntryEvent: JournalEntry.Event,
    occurredAt: Instant,
    reference: JournalEntryReference
  ) extends AmountBasedJournalEntryEvent
  case class PaymentJournalEntryEvent(
    amount: JournalEntryAmount,
    journalEntryEvent: JournalEntry.Event,
    assetAccount: JournalEntry.Account,
    contraAssetAccount: JournalEntry.Account,
    occurredAt: Instant,
    reference: JournalEntryReference
  ) extends AmountBasedJournalEntryEvent
  case class ReturnedPaymentJournalEntryEvent(
    amount: JournalEntryAmount,
    journalEntryEvent: JournalEntry.Event,
    assetAccount: JournalEntry.Account,
    occurredAt: Instant,
    reference: JournalEntryReference
  ) extends AmountBasedJournalEntryEvent
  case class ContraJournalEntryEvent(
    amount: JournalEntryAmount,
    amountBreakdown: Option[AmountBreakdown],
    paidAmount: JournalEntryAmount,
    contraMode: ContraMode,
    journalEntryEvent: JournalEntry.Event,
    assetAccount: JournalEntry.Account,
    contraAccount: JournalEntry.Account,
    occurredAt: Instant,
    reference: JournalEntryReference
  ) extends AmountBasedJournalEntryEvent
  case class CreditNoteVoidedJournalEntryEvent(
    journalEntryEvent: JournalEntry.Event,
    occurredAt: Instant,
    principleAccount: JournalEntry.Account,
    reference: JournalEntryReference
  ) extends JournalEntryEvent {
    lazy val isZero: Boolean = false
  }
  case class MarkPaidJournalEntryEvent(
    amount: JournalEntryAmount,
    journalEntryEvent: JournalEntry.Event,
    occurredAt: Instant,
    reference: JournalEntryReference
  ) extends JournalEntryEvent {
    lazy val isZero: Boolean = false
  }
  case class MarkUncollectibleJournalEntryEvent(
    journalEntryEvent: JournalEntry.Event,
    occurredAt: Instant,
    principleAccount: JournalEntry.Account,
    reference: JournalEntryReference
  ) extends JournalEntryEvent {
    lazy val isZero: Boolean = false
  }
  case class VoidJournalEntryEvent(
    journalEntryEvent: JournalEntry.Event,
    occurredAt: Instant,
    principleAccount: JournalEntry.Account,
    reference: JournalEntryReference
  ) extends JournalEntryEvent {
    lazy val isZero: Boolean = false
  }
  case class FxLossJournalEntryEvent(
    amount: JournalEntryAmount,
    journalEntryEvent: JournalEntry.Event,
    assetAccount: JournalEntry.Account,
    occurredAt: Instant,
    reference: JournalEntryReference
  ) extends AmountBasedJournalEntryEvent
  case class RecoverableJournalEntryEvent(
    amount: JournalEntryAmount,
    journalEntryEvent: JournalEntry.Event,
    assetAccount: JournalEntry.Account,
    occurredAt: Instant,
    reference: JournalEntryReference
  ) extends AmountBasedJournalEntryEvent

  case class State(
    settlementCurrency: String,
    presentmentCurrency: String,
    events: Seq[JournalEntryEvent],
  ) {
    lazy val isBillClosed: Boolean = events.exists {
      case _: MarkPaidJournalEntryEvent => true
      case _: VoidJournalEntryEvent => true
      case _: MarkUncollectibleJournalEntryEvent => true
      case _ => false
    }
    lazy val paidAmount: JournalEntryAmount = sum(
      events.collect {
        case e: PaymentJournalEntryEvent => e.amount
        case e: MarkPaidJournalEntryEvent => e.amount
        case e: ReturnedPaymentJournalEntryEvent => -e.amount
      },
      settlementCurrency,
      presentmentCurrency
    )
    lazy val netPaidAmount: JournalEntryAmount = paidAmount + sum(
      events.collect { case e: ContraJournalEntryEvent if e.contraMode == ContraMode.Paid => -e.amount },
      settlementCurrency,
      presentmentCurrency
    )
    lazy val undepositedAmount: JournalEntryAmount = sum(
      events.collect {
        case e: PayInvoiceJournalEntryEvent => e.amount
        case e: PaymentJournalEntryEvent if e.contraAssetAccount == JournalEntry.Account.UndepositedFunds => -e.amount
      },
      settlementCurrency,
      presentmentCurrency
    )
    lazy val contraAmount: JournalEntryAmount = sum(events.collect { case e: ContraJournalEntryEvent => e.amount }, settlementCurrency, presentmentCurrency)
    lazy val fxLossAmount: JournalEntryAmount = sum(events.collect { case e: FxLossJournalEntryEvent => e.amount }, settlementCurrency, presentmentCurrency)
    lazy val recoverableAmount: JournalEntryAmount = sum(events.collect { case e: RecoverableJournalEntryEvent => e.amount }, settlementCurrency, presentmentCurrency)
  }

  def buildJournalEntryEvents(
    billedAmount: JournalEntryAmount,
    events: Seq[BillingEvent]
  ): Seq[JournalEntryEvent] = {
    val state = sanitize(events).foldLeft(State(billedAmount.settlement.currency, billedAmount.presentment.currency, Seq.empty)) { case (state, event) =>
      event match {
        case event: PayInvoiceBillingEvent =>
          logger.info(s"Processing: ${event.occurredAt} ${event.getClass.getSimpleName} ${event.amount.settlement}")
          handlePayInvoiceBillingEvent(state, event)
        case event: MoneyMovementBillingEvent =>
          logger.info(s"Processing: ${event.occurredAt} ${event.getClass.getSimpleName} ${event.amount.settlement} ${event.amountBreakdown} ${event.assetAccount} ${event.journalEntryEvent}")
          handleMoneyMovementBillingEvent(billedAmount, state, event)
        case event: MarkUncollectibleBillingEvent =>
          logger.info(s"Processing: ${event.occurredAt} ${event.getClass.getSimpleName}")
          handleMarkUncollectibleEvent(state, event)
        case event: VoidBillingEvent =>
          logger.info(s"Processing: ${event.occurredAt} ${event.getClass.getSimpleName}")
          handleVoidEvent(billedAmount, state, event)
        case event: MarkPaidBillingEvent =>
          logger.info(s"Processing: ${event.occurredAt} ${event.getClass.getSimpleName}")
          handleMarkPaidEvent(billedAmount, state, event)
        case event: PrepaidCreditNoteIssuedBillingEvent =>
          logger.info(s"Processing: ${event.occurredAt} ${event.getClass.getSimpleName} ${event.totalAmount.settlement} ${event.amountBreakdown}")
          handlePrepaidCreditNoteIssuedEvent(state, event)
        case event: PrepaidCreditNoteVoidedBillingEvent =>
          logger.info(s"Processing: ${event.occurredAt} ${event.getClass.getSimpleName}")
          handlePrepaidCreditNoteVoidedEvent(state, event)
      }
    }

    state.events.filter { e => !e.isZero }
  }

  // We need to sanitize the events before processing them. This is to separate intentional human actions from incidental ones caused by the system.
  // As an example, when an invoice is paid, its paid time is set but its payment might be created a second later. We wouldn't want Underpayment in this case.
  private[this] def sanitize(events: Seq[BillingEvent]): Seq[BillingEvent] = {
    println(s"Sanitizing events")
    val array = events.sorted.toArray
    var index = 0

    while (index < array.length) {
      array(index) match {
        case m: MarkPaidBillingEvent =>
          val nextEvent = if ((index + 1) < array.length) Some(array(index + 1)) else None

          nextEvent.foreach {
            // We want to move the MarkPaid event to be after the payment events.
            // We speculate that there might be a race condition that might make the payment events to be after the invoice's paid_at.
            // We only move the payment (not contra) to before the invoice marked paid.
            case p: MoneyMovementBillingEvent if p.amount.settlement.value >= 0 =>
              if ((p.occurredAt.getEpochSecond - m.occurredAt.getEpochSecond) <= 1) {
                array(index + 1) = m
                array(index) = p
              }
            case p: PayInvoiceBillingEvent =>
              if ((p.occurredAt.getEpochSecond - m.occurredAt.getEpochSecond) <= 1) {
                array(index + 1) = m
                array(index) = p
              }
            case _ => // do nothing
          }
        case _ => // do nothing
      }

      index += 1
    }

    array.toList
  }

  def handlePrepaidCreditNoteIssuedEvent(
    state: State,
    event: PrepaidCreditNoteIssuedBillingEvent
  ): State = {
    state.copy(
      events = state.events ++ Seq(
        ContraJournalEntryEvent(
          amount = event.totalAmount,
          amountBreakdown = event.amountBreakdown,
          paidAmount = JournalEntryAmount.empty(event.totalAmount.settlement.currency, event.totalAmount.presentment.currency),
          contraMode = ContraMode.UniformUnpaid,
          journalEntryEvent = JournalEntry.Event.IssueCreditNote,
          assetAccount = JournalEntry.Account.AccountsReceivable,
          contraAccount = if (event.principleAccount == JournalEntry.Account.Revenue) {
            JournalEntry.Account.CreditNotes
          } else {
            event.principleAccount
          },
          occurredAt = event.occurredAt,
          reference = JournalEntryReference(
            chargeId = None,
            paymentIntentId = None,
            disputeId = None,
            refundId = None,
            balanceTransactionId = None,
            paymentRecordId = None,
            creditNoteId = Some(event.creditNoteId),
            creditNoteLineItemId = event.creditNoteLineItemId
          )
        )
      )
    )
  }

  def handlePrepaidCreditNoteVoidedEvent(
    state: State,
    event: PrepaidCreditNoteVoidedBillingEvent
  ): State = {
    state.copy(
      events = state.events ++ Seq(
        CreditNoteVoidedJournalEntryEvent(
          journalEntryEvent = JournalEntry.Event.VoidCreditNote,
          occurredAt = event.occurredAt,
          principleAccount = event.principleAccount,
          reference = JournalEntryReference(
            chargeId = None,
            paymentIntentId = None,
            disputeId = None,
            refundId = None,
            balanceTransactionId = None,
            paymentRecordId = None,
            creditNoteId = Some(event.creditNoteId),
            creditNoteLineItemId = event.creditNoteLineItemId
          )
        )
      )
    )
  }

  def handleVoidEvent(
    billedAmount: JournalEntryAmount,
    state: State,
    event: VoidBillingEvent,
  ): State = {
    assert(state.netPaidAmount.isZero, s"A partially paid invoice is not supposed to be voided. Something is wrong. Net paid amount: ${state.netPaidAmount}")
    val reference = JournalEntryReference(
      chargeId = None,
      paymentIntentId = None,
      disputeId = None,
      refundId = None,
      balanceTransactionId = None,
      paymentRecordId = None,
      creditNoteId = None,
      creditNoteLineItemId = None
    )
    state.copy(
      events = state.events ++ Seq(
        VoidJournalEntryEvent(
          journalEntryEvent = JournalEntry.Event.VoidInvoice,
          occurredAt = event.occurredAt,
          principleAccount = event.principleAccount,
          reference = reference
        )
      )
    )
  }

  def handlePayInvoiceBillingEvent(
    state: State,
    event: PayInvoiceBillingEvent,
  ): State = {
    if (state.isBillClosed) {
      return state
    }
    state.copy(
      events = state.events ++ Seq(
        PayInvoiceJournalEntryEvent(
          amount = event.amount,
          occurredAt = event.occurredAt,
          journalEntryEvent = JournalEntry.Event.PayInvoice,
          reference = JournalEntryReference(
            chargeId = None,
            paymentIntentId = None,
            disputeId = None,
            refundId = None,
            balanceTransactionId = None,
            paymentRecordId = None,
            creditNoteId = None,
            creditNoteLineItemId = None
          )
        )
      )
    )
  }


  def handleMarkPaidEvent(
    billedAmount: JournalEntryAmount,
    state: State,
    event: MarkPaidBillingEvent,
  ): State = {
    state.copy(
      events = state.events ++ Seq(
        MarkPaidJournalEntryEvent(
          amount = billedAmount - state.netPaidAmount,
          journalEntryEvent = JournalEntry.Event.MarkPaid,
          occurredAt = event.occurredAt,
          reference = JournalEntryReference(
            chargeId = None,
            paymentIntentId = None,
            disputeId = None,
            refundId = None,
            balanceTransactionId = None,
            paymentRecordId = None,
            creditNoteId = None,
            creditNoteLineItemId = None
          )
        )
      )
    )
  }

  def handleMarkUncollectibleEvent(
    state: State,
    event: MarkUncollectibleBillingEvent,
  ): State = {
    state.copy(
      events = state.events ++ Seq(
        MarkUncollectibleJournalEntryEvent(
          journalEntryEvent = JournalEntry.Event.MarkUncollectible,
          occurredAt = event.occurredAt,
          principleAccount = event.principleAccount,
          reference = JournalEntryReference(
            chargeId = None,
            paymentIntentId = None,
            disputeId = None,
            refundId = None,
            balanceTransactionId = None,
            paymentRecordId = None,
            creditNoteId = None,
            creditNoteLineItemId = None
          )
        )
      )
    )
  }

  def handleMoneyMovementBillingEvent(
    billedAmount: JournalEntryAmount,
    state: State,
    event: MoneyMovementBillingEvent,
  ): State = {
    val reference = JournalEntryReference(
      chargeId = event.chargeId,
      paymentIntentId = event.paymentIntentId,
      disputeId = event.disputeId,
      refundId = event.refundId,
      balanceTransactionId = event.balanceTransactionId,
      paymentRecordId = event.paymentRecordId,
      creditNoteId = event.creditNoteId,
      creditNoteLineItemId = event.creditNoteLineItemId
    )


    if (state.isBillClosed) {
      logger.info(s"Handle money movement. Bill was previously closed. event=${event.amount.settlement} asset=${event.assetAccount} previouslyPaid=${state.netPaidAmount} billedAmount=$billedAmount")
      if (event.amount.settlement.value >= 0) {
        var creditFxLoss = JournalEntryAmount(
          settlement = Amount(Math.min(state.fxLossAmount.settlement.value, event.amount.settlement.value), event.settlementCurrency),
          presentment = Amount(0L, event.presentmentCurrency)
        )
        var creditUndepositedFundsAmount = JournalEntryAmount(
          settlement = Amount(
            Math.min(event.amount.settlement.value - creditFxLoss.settlement.value, state.undepositedAmount.settlement.value),
            event.settlementCurrency
          ),
          presentment = Amount(
            Math.min(event.amount.presentment.value - creditFxLoss.presentment.value, state.undepositedAmount.presentment.value),
            event.presentmentCurrency
          )
        )
        var recoverable = event.amount - creditFxLoss - creditUndepositedFundsAmount

        state.copy(
          events = state.events ++ Seq(
            PaymentJournalEntryEvent(
              amount = creditUndepositedFundsAmount,
              journalEntryEvent = event.journalEntryEvent,
              assetAccount = event.assetAccount,
              contraAssetAccount = JournalEntry.Account.UndepositedFunds,
              occurredAt = event.occurredAt,
              reference = reference
            ),
            FxLossJournalEntryEvent(
              amount = -creditFxLoss,
              journalEntryEvent = event.journalEntryEvent,
              assetAccount = event.assetAccount,
              occurredAt = event.occurredAt,
              reference = reference
            ),
            RecoverableJournalEntryEvent(
              amount = recoverable,
              journalEntryEvent = event.journalEntryEvent,
              assetAccount = event.assetAccount,
              occurredAt = event.occurredAt,
              reference = reference
            )
          )
        )
      } else {
        val debitRecoverablePresentment = Math.min(state.recoverableAmount.presentment.value, -event.amount.presentment.value)
        val contraPresentmentAmount = Math.min(-event.amount.presentment.value - debitRecoverablePresentment, billedAmount.presentment.value - state.contraAmount.presentment.value)
        val isContraCompletion = (state.contraAmount.presentment.value + contraPresentmentAmount) >= billedAmount.presentment.value

        var debitRecoverableSettlement = Math.min(state.recoverableAmount.settlement.value, -event.amount.settlement.value)
        var contraSettlementAmount = 0L
        var overReturnSettlementAmount = 0L
        var fxLoss = 0L

        if (isContraCompletion) {
          contraSettlementAmount = billedAmount.settlement.value - state.contraAmount.settlement.value
          fxLoss = -event.amount.settlement.value - contraSettlementAmount - debitRecoverableSettlement
        } else {
          contraSettlementAmount = -event.amount.settlement.value - debitRecoverableSettlement
        }

        state.copy(
          events = state.events ++ Seq(
            ContraJournalEntryEvent(
              amount = JournalEntryAmount(settlement = Amount(contraSettlementAmount, event.settlementCurrency), presentment = Amount(contraPresentmentAmount, event.presentmentCurrency)),
              amountBreakdown = event.amountBreakdown,
              paidAmount = state.netPaidAmount,
              contraMode = ContraMode.Paid,
              journalEntryEvent = event.journalEntryEvent,
              assetAccount = event.assetAccount,
              contraAccount = event.contraAccount.get,
              occurredAt = event.occurredAt,
              reference = reference
            ),
            RecoverableJournalEntryEvent(
              amount = JournalEntryAmount(settlement = Amount(-debitRecoverableSettlement, event.settlementCurrency), presentment = Amount(-debitRecoverablePresentment, event.presentmentCurrency)),
              journalEntryEvent = event.journalEntryEvent,
              assetAccount = event.assetAccount,
              occurredAt = event.occurredAt,
              reference = reference
            ),
            FxLossJournalEntryEvent(
              amount = JournalEntryAmount(settlement = Amount(fxLoss, event.settlementCurrency), presentment = Amount(0L, event.presentmentCurrency)),
              journalEntryEvent = event.journalEntryEvent,
              assetAccount = event.assetAccount,
              occurredAt = event.occurredAt,
              reference = reference
            )
          )
        )
      }
    } else {
      logger.info(s"Handle money movement. Bill is still open. event=${event.amount.settlement} asset=${event.assetAccount} previouslyPaid=${state.netPaidAmount} billedAmount=$billedAmount")
      if (event.amount.settlement.value >= 0) {
        val paidPresentmentAmount = Math.min(billedAmount.presentment.value - state.paidAmount.presentment.value, event.amount.presentment.value)
        val recoverablePresentmentAmount = Math.max(0, event.amount.presentment.value - paidPresentmentAmount)
        val isPaidCompletion = (state.paidAmount.presentment.value + event.amount.presentment.value) >= billedAmount.presentment.value

        var offsetFxLossAssetAccount = event.assetAccount
        var paidSettlementAmount = 0L
        var recoverableSettlementAmount = 0L
        var creditFxLoss = 0L
        var fxLoss = 0L

        if (isPaidCompletion) {
          if ((state.paidAmount.settlement.value + event.amount.settlement.value) >= billedAmount.settlement.value) {
            paidSettlementAmount = Math.min(billedAmount.settlement.value - state.paidAmount.settlement.value, event.amount.settlement.value)
            if (recoverablePresentmentAmount > 0) {
              recoverableSettlementAmount = event.amount.settlement.value - paidSettlementAmount
            } else {
              creditFxLoss = event.amount.settlement.value - paidSettlementAmount
            }
          } else {
            paidSettlementAmount = event.amount.settlement.value
            offsetFxLossAssetAccount = if (event.payAndMoveMoney) { JournalEntry.Account.AccountsReceivable } else { JournalEntry.Account.UndepositedFunds }
            fxLoss = billedAmount.settlement.value - state.paidAmount.settlement.value - event.amount.settlement.value
          }
        } else {
          paidSettlementAmount = event.amount.settlement.value
        }

        state.copy(
          events = state.events ++ Seq(
            PaymentJournalEntryEvent(
              amount = JournalEntryAmount(settlement = Amount(paidSettlementAmount, event.settlementCurrency), presentment = Amount(paidPresentmentAmount, event.presentmentCurrency)),
              journalEntryEvent = event.journalEntryEvent,
              assetAccount = event.assetAccount,
              contraAssetAccount = if (event.payAndMoveMoney) { JournalEntry.Account.AccountsReceivable } else { JournalEntry.Account.UndepositedFunds },
              occurredAt = event.occurredAt,
              reference = reference
            ),
            FxLossJournalEntryEvent(
              amount = JournalEntryAmount(settlement = Amount(fxLoss - creditFxLoss, event.settlementCurrency), presentment = Amount(0L, event.presentmentCurrency)),
              journalEntryEvent = event.journalEntryEvent,
              assetAccount = offsetFxLossAssetAccount,
              occurredAt = event.occurredAt,
              reference = reference
            ),
            RecoverableJournalEntryEvent(
              amount = JournalEntryAmount(
                settlement = Amount(recoverableSettlementAmount, event.settlementCurrency),
                presentment = Amount(recoverablePresentmentAmount, event.presentmentCurrency)
              ),
              journalEntryEvent = event.journalEntryEvent,
              assetAccount = event.assetAccount,
              occurredAt = event.occurredAt,
              reference = reference
            )
          )
        )
      } else {
        val creditPaidSettlement = Math.min(state.paidAmount.settlement.value, -event.amount.settlement.value)
        val fxLoss = -event.amount.settlement.value - creditPaidSettlement
        state.copy(
          events = state.events ++ Seq(
            ReturnedPaymentJournalEntryEvent(
              amount = JournalEntryAmount(settlement = Amount(creditPaidSettlement, event.settlementCurrency), presentment = -event.amount.presentment),
              journalEntryEvent = event.journalEntryEvent,
              assetAccount = event.assetAccount,
              occurredAt = event.occurredAt,
              reference = reference
            ),
            FxLossJournalEntryEvent(
              amount = JournalEntryAmount(settlement = Amount(fxLoss, event.settlementCurrency), presentment = Amount(0L, event.presentmentCurrency)),
              journalEntryEvent = event.journalEntryEvent,
              assetAccount = event.assetAccount,
              occurredAt = event.occurredAt,
              reference = reference
            )
          )
        )
      }
    }
  }

  def buildJournalEntries(
    settlementCurrency: String,
    presentmentCurrency: String,
    invoicingEntries: Seq[JournalEntry],
    events: Seq[JournalEntryEvent],
  ): Seq[JournalEntry] = {
    events.foldLeft(Seq.empty[JournalEntry]) { case (priorEntries, event) =>
      val newEntries = event match {
        case p: PayInvoiceJournalEntryEvent =>
          logger.info(s"Processing event: ${p.occurredAt} ${p.getClass.getSimpleName} ${p.amount.settlement}")
          Seq(p.toJournalEntry(
            debit = JournalEntry.Account.UndepositedFunds,
            credit = JournalEntry.Account.AccountsReceivable,
            amount = p.amount,
          ))
        case p: PaymentJournalEntryEvent =>
          logger.info(s"Processing event: ${p.occurredAt} ${p.getClass.getSimpleName} ${p.amount.settlement} ${p.assetAccount}")
          Seq(p.toJournalEntry(
            debit = p.assetAccount,
            credit = p.contraAssetAccount,
            amount = p.amount,
          ))
        case p: ReturnedPaymentJournalEntryEvent =>
          logger.info(s"Processing event: ${p.occurredAt} ${p.getClass.getSimpleName} ${p.amount.settlement} ${p.assetAccount}")
          Seq(p.toJournalEntry(
            debit = JournalEntry.Account.AccountsReceivable,
            credit = p.assetAccount,
            amount = p.amount,
          ))
        case f: FxLossJournalEntryEvent =>
          logger.info(s"Processing event: ${f.occurredAt} ${f.getClass.getSimpleName} ${f.amount.settlement} ${f.assetAccount}")
          Seq(f.toJournalEntry(
            debit = JournalEntry.Account.Loss,
            credit = f.assetAccount,
            amount = f.amount,
          ))
        case m: MarkPaidJournalEntryEvent =>
          logger.info(s"Processing event: ${m.occurredAt} ${m.getClass.getSimpleName}")
          bookMarkPaid(
            settlementCurrency = settlementCurrency,
            presentmentCurrency = presentmentCurrency,
            entries = invoicingEntries ++ priorEntries,
            markPaidAt = m.occurredAt,
            reference = m.reference,
          )
        case r: RecoverableJournalEntryEvent =>
          logger.info(s"Processing event: ${r.occurredAt} ${r.getClass.getSimpleName} ${r.amount.settlement} ${r.assetAccount}")
          Seq(r.toJournalEntry(
            debit = r.assetAccount,
            credit = JournalEntry.Account.Recoverables,
            amount = r.amount,
          ))
        case m: MarkUncollectibleJournalEntryEvent =>
          logger.info(s"Processing event: ${m.occurredAt} ${m.getClass.getSimpleName}")
          bookUncollectible(
            settlementCurrency = settlementCurrency,
            presentmentCurrency = presentmentCurrency,
            entries = invoicingEntries ++ priorEntries,
            uncollectibleAt = m.occurredAt,
            principleAccount = m.principleAccount,
            reference = m.reference,
          )
        case u: VoidJournalEntryEvent =>
          logger.info(s"Processing event: ${u.occurredAt} ${u.getClass.getSimpleName}")
          bookVoid(
            settlementCurrency = settlementCurrency,
            presentmentCurrency = presentmentCurrency,
            entries = invoicingEntries ++ priorEntries,
            voidedAt = u.occurredAt,
            principleAccount = u.principleAccount,
            reference = u.reference,
          )
        case u: CreditNoteVoidedJournalEntryEvent =>
          logger.info(s"Processing event: ${u.occurredAt} ${u.getClass.getSimpleName}")
          bookCreditNoteVoided(
            settlementCurrency = settlementCurrency,
            presentmentCurrency = presentmentCurrency,
            entries = invoicingEntries ++ priorEntries,
            voidedAt = u.occurredAt,
            principleAccount = u.principleAccount,
            reference = u.reference,
          )
        case c: ContraJournalEntryEvent =>
          logger.info(s"Processing event: ${c.occurredAt} ${c.getClass.getSimpleName} ${c.amount.settlement} ${c.amountBreakdown} ${c.contraMode} ${c.paidAmount.settlement} ${c.contraAccount} ${c.assetAccount}")
          bookContra(
            entries = invoicingEntries ++ priorEntries,
            paidAmount = c.paidAmount,
            contraAmount = c.amount,
            contraAmountBreakdown = c.amountBreakdown,
            contraMode = c.contraMode,
            contraOccurredAt = Some(c.occurredAt),
            assetAccount = c.assetAccount,
            contraAccount = c.contraAccount,
            contraEvent = c.journalEntryEvent,
            reference = c.reference,
          )
      }

      priorEntries ++ newEntries
    }
  }

  private[this] def bookCreditNoteVoided(
    settlementCurrency: String,
    presentmentCurrency: String,
    entries: Seq[JournalEntry],
    voidedAt: Instant,
    principleAccount: JournalEntry.Account,
    reference: JournalEntryReference
  ): Seq[JournalEntry] = {
    val creditNoteId = reference.creditNoteId.get
    val voidPeriod = getAccountingPeriod(voidedAt)
    val creditNoteEntries = entries.filter { e => e.creditNoteId.contains(creditNoteId) && e.creditNoteLineItemId == reference.creditNoteLineItemId }

    if (principleAccount == JournalEntry.Account.Revenue) {
      val (entriesBefore, entriesAfter) = creditNoteEntries.partition(_.accountingPeriod.isBefore(voidPeriod))

      val contraAmount = sumAccount(creditNoteEntries, JournalEntry.Account.CreditNotes).getOrElse(JournalEntryAmount.empty(settlementCurrency, presentmentCurrency))

      val canceledPastRevenue = -sumAccount(entriesBefore, JournalEntry.Account.Revenue).getOrElse(JournalEntryAmount.empty(settlementCurrency, presentmentCurrency))
      val canceledRecognizeRevenueEntries = entriesAfter.filter { e => e.debit == JournalEntry.Account.Revenue || e.credit == JournalEntry.Account.Revenue }
      val canceledFutureRevenue = -sumAccount(canceledRecognizeRevenueEntries, JournalEntry.Account.Revenue).getOrElse(JournalEntryAmount.empty(settlementCurrency, presentmentCurrency))

      val catchUpEntries = Seq(
        makeJournalEntry(
          accountingPeriod = voidPeriod,
          debit = JournalEntry.Account.AccountsReceivable,
          credit = JournalEntry.Account.CreditNotes,
          settlementAmount = contraAmount.settlement.value,
          settlementCurrency = contraAmount.settlement.currency,
          presentmentAmount = contraAmount.presentment.value,
          presentmentCurrency = contraAmount.presentment.currency,
          occurredAt = voidedAt,
          event = JournalEntry.Event.VoidCreditNote,
          reference = reference,
        ),
        makeJournalEntry(
          accountingPeriod = voidPeriod,
          debit = JournalEntry.Account.AccountsReceivable,
          credit = JournalEntry.Account.Revenue,
          settlementAmount = canceledPastRevenue.settlement.value,
          settlementCurrency = canceledPastRevenue.settlement.currency,
          presentmentAmount = canceledPastRevenue.presentment.value,
          presentmentCurrency = canceledPastRevenue.presentment.currency,
          occurredAt = voidedAt,
          event = JournalEntry.Event.VoidCreditNote,
          reference = reference,
        ),
        makeJournalEntry(
          accountingPeriod = voidPeriod,
          debit = JournalEntry.Account.AccountsReceivable,
          credit = JournalEntry.Account.DeferredRevenue,
          settlementAmount = canceledFutureRevenue.settlement.value,
          settlementCurrency = canceledFutureRevenue.settlement.currency,
          presentmentAmount = canceledFutureRevenue.presentment.value,
          presentmentCurrency = canceledFutureRevenue.presentment.currency,
          occurredAt = voidedAt,
          event = JournalEntry.Event.VoidCreditNote,
          reference = reference,
        ),
      )

      val rebookRevenueEntries = canceledRecognizeRevenueEntries.map { e =>
        makeJournalEntry(
          accountingPeriod = e.accountingPeriod,
          debit = e.debit,
          credit = e.credit,
          settlementAmount = -e.settlementAmount,
          settlementCurrency = e.settlementCurrency,
          presentmentAmount = -e.presentmentAmount,
          presentmentCurrency = e.presentmentCurrency,
          occurredAt = voidedAt,
          event = JournalEntry.Event.RecognizeRevenue,
          reference = reference,
        )
      }

      catchUpEntries ++ rebookRevenueEntries
    } else {
      val canceledPrincipleAmount = -sumAccount(creditNoteEntries, principleAccount).getOrElse(JournalEntryAmount.empty(settlementCurrency, presentmentCurrency))
      Seq(makeJournalEntry(
        accountingPeriod = voidPeriod,
        debit = JournalEntry.Account.AccountsReceivable,
        credit = principleAccount,
        settlementAmount = canceledPrincipleAmount.settlement.value,
        settlementCurrency = canceledPrincipleAmount.settlement.currency,
        presentmentAmount = canceledPrincipleAmount.presentment.value,
        presentmentCurrency = canceledPrincipleAmount.presentment.currency,
        occurredAt = voidedAt,
        event = JournalEntry.Event.VoidCreditNote,
        reference = reference,
      ))
    }
  }


  private[this] def bookContra(
    entries: Seq[JournalEntry],
    paidAmount: JournalEntryAmount,
    contraAmount: JournalEntryAmount,
    contraAmountBreakdown: Option[AmountBreakdown],
    contraMode: ContraMode,
    contraOccurredAt: Option[Instant],
    assetAccount: JournalEntry.Account,
    contraAccount: JournalEntry.Account,
    contraEvent: JournalEntry.Event,
    reference: JournalEntryReference,
  ): Seq[JournalEntry] = {
    if (contraOccurredAt.isEmpty) {
      return Seq.empty
    }

    val contraPeriod = getAccountingPeriod(contraOccurredAt.get)

    if (contraAccount.getAccountCategory() == AccountCategory.ContraRevenue) {
      val totalCancelableRevenue = sumAccount(entries, JournalEntry.Account.Revenue)
        .getOrElse(JournalEntryAmount.empty(contraAmount.settlement.currency, contraAmount.presentment.currency)) -
        sumAccountCategory(entries, JournalEntry.AccountCategory.ContraRevenue)
          .getOrElse(JournalEntryAmount.empty(contraAmount.settlement.currency, contraAmount.presentment.currency))
      val cancelableRevenuePeriods = entries
        .groupBy { e => e.attributionPeriod.getOrElse(e.accountingPeriod) }
        .map { case (accountingPeriod, entries) =>
          val amount = sumAccount(entries, JournalEntry.Account.Revenue)
            .getOrElse(JournalEntryAmount.empty(contraAmount.settlement.currency, contraAmount.presentment.currency))
          val contra = sumAccountCategory(entries, JournalEntry.AccountCategory.ContraRevenue)
            .getOrElse(JournalEntryAmount.empty(contraAmount.settlement.currency, contraAmount.presentment.currency))
          JournalEntryPeriod(
            startedAt = accountingPeriod,
            settlementAmount = amount.settlement.value - contra.settlement.value,
            presentmentAmount = amount.presentment.value - contra.presentment.value,
          )
        }
        .toList
        .sortBy(_.startedAt)
      val paidByCredits = -Seq(JournalEntry.Account.PaidCreditGrants, JournalEntry.Account.PromotionalCreditGrants)
        .map { account =>
          sumAccount2(entries, account, contraAmount.settlement.currency, contraAmount.presentment.currency)
        }
        .reduce(_ + _)

      val cancelPeriods = computeContraPeriods(contraAmount.toDualAmount(), paidAmount.toDualAmount(), cancelableRevenuePeriods, contraMode)
      val (recognizedPeriods, deferredPeriods) = cancelPeriods.partition(_.startedAt.toEpochMilli < contraPeriod.toEpochMilli)
      val canceledRevenueEntries = deferredPeriods.map { period =>
        makeJournalEntry(
          accountingPeriod = getAccountingPeriod(period.startedAt),
          debit = JournalEntry.Account.Revenue,
          credit = JournalEntry.Account.DeferredRevenue,
          settlementAmount = period.settlementAmount,
          settlementCurrency = contraAmount.settlement.currency,
          presentmentAmount = period.presentmentAmount,
          presentmentCurrency = contraAmount.presentment.currency,
          occurredAt = contraOccurredAt.get,
          event = contraEvent,
          reversedEvent = Some(JournalEntry.Event.RecognizeRevenue),
          reference = reference,
        )
      }

      val contraEntries = recognizedPeriods.map { period =>
        makeJournalEntry(
          accountingPeriod = contraPeriod,
          attributionPeriod = Some(period.startedAt),
          debit = contraAccount,
          credit = assetAccount,
          settlementAmount = period.settlementAmount,
          settlementCurrency = contraAmount.settlement.currency,
          presentmentAmount = period.presentmentAmount,
          presentmentCurrency = contraAmount.presentment.currency,
          occurredAt = contraOccurredAt.get,
          event = contraEvent,
          reference = reference,
        )
      }

      Seq(
        makeJournalEntry(
          accountingPeriod = contraPeriod,
          debit = JournalEntry.Account.DeferredRevenue,
          credit = assetAccount,
          settlementAmount = deferredPeriods.map(_.settlementAmount).sum,
          settlementCurrency = contraAmount.settlement.currency,
          presentmentAmount = deferredPeriods.map(_.presentmentAmount).sum,
          presentmentCurrency = contraAmount.presentment.currency,
          occurredAt = contraOccurredAt.get,
          event = contraEvent,
          reference = reference,
        ),
      ) ++ canceledRevenueEntries ++ contraEntries
    } else {

      val cancelPaidSettlement = paidAmount.settlement.min(contraAmount.settlement)
      val cancelPaidPresentment = paidAmount.presentment.min(contraAmount.presentment)
      val cancelUnpaidSettlement = (contraAmount.settlement - cancelPaidSettlement).max(Amount(0L, contraAmount.settlement.currency))
      val cancelUnpaidPresentment = (contraAmount.presentment - cancelPaidPresentment).max(Amount(0L, contraAmount.presentment.currency))

      Seq(
        makeJournalEntry(
          accountingPeriod = contraPeriod,
          debit = contraAccount,
          credit = assetAccount,
          settlementAmount = cancelPaidSettlement.value,
          settlementCurrency = cancelPaidSettlement.currency,
          presentmentAmount = cancelPaidPresentment.value,
          presentmentCurrency = cancelPaidPresentment.currency,
          occurredAt = contraOccurredAt.get,
          event = contraEvent,
          reference = reference,
        ),
        makeJournalEntry(
          accountingPeriod = contraPeriod,
          debit = contraAccount,
          credit = JournalEntry.Account.AccountsReceivable,
          settlementAmount = cancelUnpaidSettlement.value,
          settlementCurrency = cancelUnpaidSettlement.currency,
          presentmentAmount = cancelUnpaidPresentment.value,
          presentmentCurrency = cancelUnpaidPresentment.currency,
          occurredAt = contraOccurredAt.get,
          event = contraEvent,
          reference = reference,
        )
      )
    }
  }

  private[this] def bookMarkPaid(
    settlementCurrency: String,
    presentmentCurrency: String,
    entries: Seq[JournalEntry],
    markPaidAt: Instant,
    reference: JournalEntryReference,
  ): Seq[JournalEntry] = {
    val arAmount = sumAccount(entries, JournalEntry.Account.AccountsReceivable).getOrElse(JournalEntryAmount.empty(settlementCurrency, presentmentCurrency))
    Seq(makeJournalEntry(
      accountingPeriod = getAccountingPeriod(markPaidAt),
      debit = JournalEntry.Account.Underpayment,
      credit = JournalEntry.Account.AccountsReceivable,
      settlementAmount = arAmount.settlement.value,
      settlementCurrency = settlementCurrency,
      presentmentAmount = arAmount.presentment.value,
      presentmentCurrency = presentmentCurrency,
      occurredAt = markPaidAt,
      event = JournalEntry.Event.MarkPaid,
      reference = reference,
    ))
  }


  private[this] def bookUncollectible(
    settlementCurrency: String,
    presentmentCurrency: String,
    entries: Seq[JournalEntry],
    principleAccount: JournalEntry.Account,
    uncollectibleAt: Instant,
    reference: JournalEntryReference,
  ): Seq[JournalEntry] = {
    val uncollectiblePeriod = getAccountingPeriod(uncollectibleAt)
    val currentAR = sumAccount(entries, JournalEntry.Account.AccountsReceivable).getOrElse(JournalEntryAmount.empty(settlementCurrency, presentmentCurrency))

    if (principleAccount == JournalEntry.Account.Revenue) {
      val recognizedRevenues = entries
        .groupBy(_.accountingPeriod)
        .toList
        .map { case (period, es) =>
          val revenue = (
            sumAccount(es, JournalEntry.Account.Revenue).getOrElse(JournalEntryAmount.empty(settlementCurrency, presentmentCurrency)) -
              sumAccountCategory(es, JournalEntry.AccountCategory.ContraRevenue).getOrElse(JournalEntryAmount.empty(settlementCurrency, presentmentCurrency))
            )
          JournalEntryPeriod(
            startedAt = period,
            settlementAmount = revenue.settlement.value,
            presentmentAmount = revenue.presentment.value,
          )
        }
        .sortBy(_.startedAt)

      val cancelRevenuePeriods = fillFromTheBack(currentAR.toDualAmount(), recognizedRevenues)
      val (cancelRevenuePeriodsBefore, cancelRevenuePeriodsAfter) = cancelRevenuePeriods.partition(_.startedAt.isBefore(uncollectiblePeriod))

      val contraEntries = cancelRevenuePeriodsBefore.map { period =>
        makeJournalEntry(
          accountingPeriod = uncollectiblePeriod,
          attributionPeriod = Some(period.startedAt),
          debit = JournalEntry.Account.BadDebt,
          credit = JournalEntry.Account.AccountsReceivable,
          settlementAmount = period.settlementAmount,
          settlementCurrency = settlementCurrency,
          presentmentAmount = period.presentmentAmount,
          presentmentCurrency = presentmentCurrency,
          occurredAt = uncollectibleAt,
          event = JournalEntry.Event.MarkUncollectible,
          reference = reference,
        )
      }

      val cancelArEntries = Seq(
        makeJournalEntry(
          accountingPeriod = uncollectiblePeriod,
          debit = JournalEntry.Account.DeferredRevenue,
          credit = JournalEntry.Account.AccountsReceivable,
          settlementAmount = cancelRevenuePeriodsAfter.map(_.settlementAmount).sum,
          settlementCurrency = settlementCurrency,
          presentmentAmount = cancelRevenuePeriodsAfter.map(_.presentmentAmount).sum,
          presentmentCurrency = presentmentCurrency,
          occurredAt = uncollectibleAt,
          event = JournalEntry.Event.MarkUncollectible,
          reference = reference,
        ),
      )

      val cancelFutureRevenueEntries = cancelRevenuePeriodsAfter.map { period =>
        makeJournalEntry(
          accountingPeriod = period.startedAt,
          debit = JournalEntry.Account.Revenue,
          credit = JournalEntry.Account.DeferredRevenue,
          settlementAmount = period.settlementAmount,
          settlementCurrency = settlementCurrency,
          presentmentAmount = period.presentmentAmount,
          presentmentCurrency = presentmentCurrency,
          occurredAt = uncollectibleAt,
          event = JournalEntry.Event.MarkUncollectible,
          reversedEvent = Some(JournalEntry.Event.RecognizeRevenue),
          reference = reference,
        )
      }

      cancelArEntries ++ cancelFutureRevenueEntries ++ contraEntries
    } else {
      Seq(makeJournalEntry(
        accountingPeriod = uncollectiblePeriod,
        debit = principleAccount,
        credit = JournalEntry.Account.AccountsReceivable,
        settlementAmount = currentAR.settlement.value,
        settlementCurrency = settlementCurrency,
        presentmentAmount = currentAR.presentment.value,
        presentmentCurrency = presentmentCurrency,
        occurredAt = uncollectibleAt,
        event = JournalEntry.Event.MarkUncollectible,
        reference = reference,
      ))
    }
  }

  private[this] def bookVoid(
    settlementCurrency: String,
    presentmentCurrency: String,
    entries: Seq[JournalEntry],
    voidedAt: Instant,
    principleAccount: JournalEntry.Account,
    reference: JournalEntryReference,
  ): Seq[JournalEntry] = {
    val voidPeriod = getAccountingPeriod(voidedAt)

    if (principleAccount == JournalEntry.Account.Revenue) {
      val contraAmounts = JournalEntry.Account.values.filter(_.getAccountCategory() == JournalEntry.AccountCategory.ContraRevenue)
        .map { contraAccount =>
          contraAccount -> sumAccount(entries, contraAccount).getOrElse(JournalEntryAmount.empty(settlementCurrency, presentmentCurrency))
        }
        .toList
      val moveToVoidEntries = entries
        .filter { e =>
          Seq(e.debit, e.credit).exists { c =>
            c.getAccountCategory() == JournalEntry.AccountCategory.ContraRevenue && c != JournalEntry.Account.Voids
          }
        }
        .map { entry =>
          if (entry.credit.getAccountCategory() == JournalEntry.AccountCategory.ContraRevenue) {
            entry.swap()
          } else {
            entry
          }
        }
        .map { entry =>
          makeJournalEntry(
            accountingPeriod = voidPeriod,
            attributionPeriod = entry.attributionPeriod,
            debit = JournalEntry.Account.Voids,
            credit = entry.debit,
            settlementAmount = entry.settlementAmount,
            settlementCurrency = entry.settlementCurrency,
            presentmentAmount = entry.presentmentAmount,
            presentmentCurrency = entry.presentmentCurrency,
            occurredAt = voidedAt,
            event = JournalEntry.Event.VoidInvoice,
            reference = reference
          )
        }

      val (beforeEntries, afterEntries) = entries.partition(_.accountingPeriod.isBefore(voidPeriod))
      val cancelableRevenuePeriods = beforeEntries
        .groupBy { e => e.attributionPeriod.getOrElse(e.accountingPeriod) }
        .map { case (accountingPeriod, entries) =>
          val amount = sumAccount2(entries, JournalEntry.Account.Revenue, settlementCurrency, presentmentCurrency)
          val contra = sumAccountCategory2(entries, JournalEntry.AccountCategory.ContraRevenue, settlementCurrency, presentmentCurrency)
          JournalEntryPeriod(
            startedAt = accountingPeriod,
            settlementAmount = amount.settlement.value - contra.settlement.value,
            presentmentAmount = amount.presentment.value - contra.presentment.value,
          )
        }
        .toList
        .sortBy(_.startedAt)

      val voidEntries = cancelableRevenuePeriods.map { period =>
        makeJournalEntry(
          accountingPeriod = voidPeriod,
          attributionPeriod = Some(period.startedAt),
          debit = JournalEntry.Account.Voids,
          credit = JournalEntry.Account.AccountsReceivable,
          settlementAmount = period.settlementAmount,
          settlementCurrency = settlementCurrency,
          presentmentAmount = period.presentmentAmount,
          presentmentCurrency = presentmentCurrency,
          occurredAt = voidedAt,
          event = JournalEntry.Event.VoidInvoice,
          reference = reference
        )
      }

      val futureRevenue = sumAccount(afterEntries, JournalEntry.Account.Revenue).getOrElse(JournalEntryAmount.empty(settlementCurrency, presentmentCurrency)) - sumAccountCategory(afterEntries, JournalEntry.AccountCategory.ContraRevenue).getOrElse(JournalEntryAmount.empty(settlementCurrency, presentmentCurrency))
      val cancelArEntries = Seq(
        makeJournalEntry(
          accountingPeriod = voidPeriod,
          debit = JournalEntry.Account.DeferredRevenue,
          credit = JournalEntry.Account.AccountsReceivable,
          settlementAmount = futureRevenue.settlement.value,
          settlementCurrency = futureRevenue.settlement.currency,
          presentmentAmount = futureRevenue.presentment.value,
          presentmentCurrency = futureRevenue.presentment.currency,
          occurredAt = voidedAt,
          event = JournalEntry.Event.VoidInvoice,
          reference = reference
        ),

      )

      val contraEntries = afterEntries
        .groupBy(_.accountingPeriod)
        .map { case (period, entries) =>
          val revenue = sumAccount(entries, JournalEntry.Account.Revenue).getOrElse(JournalEntryAmount.empty(settlementCurrency, presentmentCurrency)) - sumAccountCategory(entries, JournalEntry.AccountCategory.ContraRevenue).getOrElse(JournalEntryAmount.empty(settlementCurrency, presentmentCurrency))

          makeJournalEntry(
            accountingPeriod = period,
            debit = JournalEntry.Account.Revenue,
            credit = JournalEntry.Account.DeferredRevenue,
            settlementAmount = revenue.settlement.value,
            settlementCurrency = revenue.settlement.currency,
            presentmentAmount = revenue.presentment.value,
            presentmentCurrency = revenue.presentment.currency,
            occurredAt = voidedAt,
            event = JournalEntry.Event.VoidInvoice,
            reversedEvent = Some(JournalEntry.Event.RecognizeRevenue),
            reference = reference
          )
        }
        .toList

      moveToVoidEntries ++ voidEntries ++ contraEntries ++ cancelArEntries
    } else {
      val canceledPrinciple = sumAccount(entries, principleAccount).getOrElse(JournalEntryAmount.empty(settlementCurrency, presentmentCurrency))
      Seq(makeJournalEntry(
        accountingPeriod = voidPeriod,
        debit = principleAccount,
        credit = JournalEntry.Account.AccountsReceivable,
        settlementAmount = canceledPrinciple.settlement.value,
        settlementCurrency = canceledPrinciple.settlement.currency,
        presentmentAmount = canceledPrinciple.presentment.value,
        presentmentCurrency = canceledPrinciple.presentment.currency,
        occurredAt = voidedAt,
        event = JournalEntry.Event.VoidInvoice,
        reference = reference
      ))
    }
  }

  private[this] def makeJournalEntry(
    accountingPeriod: Instant,
    attributionPeriod: Option[Instant] = None,
    debit: JournalEntry.Account,
    credit: JournalEntry.Account,
    settlementAmount: Long,
    settlementCurrency: String,
    presentmentAmount: Long,
    presentmentCurrency: String,
    occurredAt: Instant,
    event: JournalEntry.Event,
    reversedEvent: Option[JournalEntry.Event] = None,
    reference: JournalEntryReference,
  ): JournalEntry = {
    JournalEntry(
      accountingPeriod = accountingPeriod,
      attributionPeriod = attributionPeriod,
      debit = debit,
      credit = credit,
      settlementAmount = settlementAmount,
      settlementCurrency = settlementCurrency,
      presentmentAmount = presentmentAmount,
      presentmentCurrency = presentmentCurrency,
      occurredAt = occurredAt,
      event = event,
      reversedEvent = reversedEvent,
      principleAccount = null,
      stripeAccountId = null,
      liveMode = false,
      revRecTransactionId = null,
      revRecTransactionType = null,
      customerId = None,
      invoiceId = None,
      invoiceLineItemId = None,
      invoiceItemId = None,
      chargeId = reference.chargeId,
      balanceTransactionId = reference.balanceTransactionId,
      disputeId = reference.disputeId,
      refundId = reference.refundId,
      customerBalanceTransactionId = None,
      paymentIntentId = reference.paymentIntentId,
      paymentRecordId = reference.paymentRecordId,
      subscriptionId = None,
      subscriptionItemId = None,
      creditBalanceTransactionId = None,
      creditNoteId = reference.creditNoteId,
      creditNoteLineItemId = reference.creditNoteLineItemId,
      productId = None,
      priceId = None,
      createdAt = null
    )
  }
}

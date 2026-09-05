package framework

import database.models.RevRecTransaction.Status
import database.models.{RevRecTransaction, RichRevRecTransaction}
import play.api.libs.json.{JsObject, Json}

object TransactionDetail {
  case class LineItem(
    id: Option[String],
    description: Option[String],
    principleAmount: Long,
    startedAt: Option[Instant],
    endedAt: Option[Instant],
    inclusiveTaxAmount: Long,
    discountAmount: Long,
    paidCreditGrantAmount: Long,
    promotionalCreditGrantAmount: Long,
    subtotal: Long,
    exclusiveTaxAmount: Long,
    total: Long,
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "id" -> id,
      "description" -> description,
      "principleAmount" -> principleAmount,
      "startedAt" -> startedAt.map(_.toEpochMilli),
      "endedAt" -> endedAt.map(_.toEpochMilli),
      "inclusiveTaxAmount" -> inclusiveTaxAmount,
      "discountAmount" -> discountAmount,
      "paidCreditGrantAmount" -> paidCreditGrantAmount,
      "promotionalCreditGrantAmount" -> promotionalCreditGrantAmount,
      "subtotal" -> subtotal,
      "exclusiveTaxAmount" -> exclusiveTaxAmount,
      "total" -> total,
    )
  }

  case class Usage(
    description: Option[String],
    startedAt: Instant,
    endedAt: Instant,
    value: Long
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "description" -> description,
      "startedAt" -> startedAt.toEpochMilli,
      "endedAt" -> endedAt.toEpochMilli,
      "value" -> value
    )
  }

  object BillingActivity {
    sealed abstract class Value extends Jsonable, Ordered[Value] {
      def timestamp: Instant
      def rank: Int

      def toJson(): JsObject = Json.obj(
        "name" -> getClass.getSimpleName,
        "timestamp" -> timestamp.toEpochMilli
      )

      override def compare(that: Value): Int = {
        val result = timestamp.compareTo(that.timestamp)

        if (result != 0) {
          result
        } else {
          rank.compareTo(that.rank)
        }
      }
    }

    case class FinalizeInvoice(timestamp: Instant) extends Value {
      val rank = 0
    }

    case class MarkUncollectibleInvoice(timestamp: Instant) extends Value {
      val rank = 10
    }

    case class VoidInvoice(timestamp: Instant) extends Value {
      val rank = 10
    }

    case class MakePayment(
      timestamp: Instant,
      chargeId: Option[String],
      paymentIntentId: Option[String],
      paymentRecordId: Option[String],
      amount: Long,
      currency: String
    ) extends Value {
      override def toJson(): JsObject = super.toJson() ++ Json.obj(
        "chargeId" -> chargeId,
        "paymentIntentId" -> paymentIntentId,
        "amount" -> amount,
        "currency" -> currency
      )

      val rank = 5
    }

    case class DebitCustomerBalance(timestamp: Instant, customerBalanceTransactionId: String, amount: Long, currency: String) extends Value {
      override def toJson(): JsObject = super.toJson() ++ Json.obj(
        "customerBalanceTransactionId" -> customerBalanceTransactionId,
        "amount" -> amount,
        "currency" -> currency
      )
      val rank = 1
    }

    case class CreditCustomerBalance(timestamp: Instant, customerBalanceTransactionId: String, amount: Long, currency: String) extends Value {
      override def toJson(): JsObject = super.toJson() ++ Json.obj(
        "customerBalanceTransactionId" -> customerBalanceTransactionId,
        "amount" -> amount,
        "currency" -> currency
      )
      val rank = 9
    }

    case class DebitCreditBalance(timestamp: Instant, creditBalanceTransactionId: String, amount: Long, currency: String) extends Value {
      override def toJson(): JsObject = super.toJson() ++ Json.obj(
        "creditBalanceTransactionId" -> creditBalanceTransactionId,
        "amount" -> amount,
        "currency" -> currency
      )
      val rank = 1
    }

    case class CreditCreditBalance(timestamp: Instant, creditBalanceTransactionId: String, amount: Long, currency: String) extends Value {
      override def toJson(): JsObject = super.toJson() ++ Json.obj(
        "creditBalanceTransactionId" -> creditBalanceTransactionId,
        "amount" -> amount,
        "currency" -> currency
      )
      val rank = 9
    }

    case class IssueCreditNote(timestamp: Instant, creditNoteId: String, amount: Long, currency: String, isPostpaid: Boolean) extends Value {
      override def toJson(): JsObject = super.toJson() ++ Json.obj(
        "creditNoteId" -> creditNoteId,
        "amount" -> amount,
        "currency" -> currency
      )
      val rank = if (!isPostpaid) { 3 } else { 6 }
    }

    case class VoidCreditNote(timestamp: Instant, creditNoteId: String, amount: Long, currency: String, isPostpaid: Boolean) extends Value {
      override def toJson(): JsObject = super.toJson() ++ Json.obj(
        "creditNoteId" -> creditNoteId,
        "amount" -> amount,
        "currency" -> currency
      )
      val rank = if (!isPostpaid) { 4 } else { 8 }
    }

    case class IssueOutOfBandRefund(timestamp: Instant, amount: Long, currency: String) extends Value {
      override def toJson(): JsObject = super.toJson() ++ Json.obj(
        "amount" -> amount,
        "currency" -> currency
      )
      val rank = 7
    }

    case class IssueRefund(timestamp: Instant, refundId: String, amount: Long, currency: String) extends Value {
      override def toJson(): JsObject = super.toJson() ++ Json.obj(
        "refundId" -> refundId,
        "amount" -> amount,
        "currency" -> currency
      )
      val rank = 7
    }

    case class FailRefund(timestamp: Instant, refundId: String, amount: Long, currency: String) extends Value {
      override def toJson(): JsObject = super.toJson() ++ Json.obj(
        "refundId" -> refundId,
        "amount" -> amount,
        "currency" -> currency
      )
      val rank = 8
    }

    case class FileDispute(timestamp: Instant, disputeId: String, amount: Long, currency: String) extends Value {
      override def toJson(): JsObject = super.toJson() ++ Json.obj(
        "disputeId" -> disputeId,
        "amount" -> amount,
        "currency" -> currency
      )
      val rank = 7
    }

    case class WinDispute(timestamp: Instant, disputeId: String, amount: Long, currency: String) extends Value {
      override def toJson(): JsObject = super.toJson() ++ Json.obj(
        "disputeId" -> disputeId,
        "amount" -> amount,
        "currency" -> currency
      )
      val rank = 8
    }
  }
}

case class TransactionDetail(
  transaction: RichRevRecTransaction
) extends Jsonable {
  import TransactionDetail.*

  val currency: String = {
    val c = transaction.base.tpe match {
      case RevRecTransaction.Type.Invoice => transaction.invoice.map(_.base.currency)
      case RevRecTransaction.Type.StandalonePaymentIntent => transaction.paymentIntent.map(_.base.currency)
      case RevRecTransaction.Type.StandaloneCharge => transaction.charge.map(_.base.currency)
      case RevRecTransaction.Type.UnbilledInvoiceItem => transaction.invoiceItem.map(_.base.currency)
      case RevRecTransaction.Type.UnbilledUsageSubscriptionItem => transaction.subscriptionItem.flatMap(_.price).map(_.base.currency)
      case RevRecTransaction.Type.StandaloneCustomerBalanceTransaction => transaction.customerBalanceTransaction.map(_.currency)
      case RevRecTransaction.Type.StandaloneCreditBalanceTransaction => transaction.creditBalanceTransaction.flatMap { t => t.base.creditCurrency.orElse(t.base.debitCurrency) }
    }
    c.getOrElse("usd")
  }
  val total: Option[Long] = transaction.base.tpe match {
    case RevRecTransaction.Type.Invoice => transaction.invoice.map(_.base.total)
    case RevRecTransaction.Type.StandalonePaymentIntent => transaction.paymentIntent.map(_.base.amount)
    case RevRecTransaction.Type.StandaloneCharge => transaction.charge.map(_.base.amount)
    case RevRecTransaction.Type.UnbilledInvoiceItem => transaction.invoiceItem.map(_.base.amount)
    case RevRecTransaction.Type.UnbilledUsageSubscriptionItem => None
    case RevRecTransaction.Type.StandaloneCustomerBalanceTransaction => transaction.customerBalanceTransaction.map(_.amount)
    case RevRecTransaction.Type.StandaloneCreditBalanceTransaction => transaction.creditBalanceTransaction.flatMap { t => t.base.creditAmount.orElse(t.base.debitAmount) }
  }
  val outstanding: Option[Long] = transaction.base.tpe match {
    case RevRecTransaction.Type.Invoice => transaction.invoice.map(_.base.amountRemaining)
    case _ => None
  }
  val paid: Option[Long] = transaction.base.tpe match {
    case RevRecTransaction.Type.Invoice => transaction.invoice.map(_.base.amountPaid)
    case RevRecTransaction.Type.StandalonePaymentIntent => if (transaction.paymentIntent.flatMap(_.charge).flatMap(_.balanceTransaction).isDefined) {
      transaction.paymentIntent.flatMap(_.charge).map(_.base.amount)
    } else {
      None
    }
    case RevRecTransaction.Type.StandaloneCharge => if (transaction.charge.flatMap(_.balanceTransaction).isDefined) {
      transaction.charge.map(_.base.amount)
    } else {
      None
    }
    case _ => None
  }
  val status: Status = transaction.base.status

  val lineItems: Seq[LineItem] = transaction.base.tpe match {
    case RevRecTransaction.Type.Invoice => transaction.invoice.toList.flatMap(_.lineItems).map { lineItem =>
      LineItem(
        id = Some(lineItem.base.id),
        description = lineItem.base.description,
        principleAmount = lineItem.totalPrincipleAmount,
        startedAt = lineItem.base.startedAt,
        endedAt = lineItem.base.endedAt,
        inclusiveTaxAmount = lineItem.totalInclusiveTaxAmount,
        discountAmount = lineItem.totalDiscountAmount,
        paidCreditGrantAmount = lineItem.totalPaidCreditGrantedAmount,
        promotionalCreditGrantAmount = lineItem.totalPromotionalCreditGrantedAmount,
        subtotal = lineItem.subtotal,
        exclusiveTaxAmount = lineItem.totalExclusiveTaxAmount,
        total = lineItem.total,
      )
    }
    case RevRecTransaction.Type.UnbilledInvoiceItem => Seq(
      LineItem(
        id = transaction.invoiceItem.map(_.base.id),
        description = transaction.invoiceItem.flatMap(_.base.description),
        principleAmount = transaction.invoiceItem.map(_.totalPrincipleAmount).getOrElse(0L),
        startedAt = transaction.invoiceItem.flatMap(_.base.startedAt),
        endedAt = transaction.invoiceItem.flatMap(_.base.endedAt),
        inclusiveTaxAmount = transaction.invoiceItem.map(_.totalInclusiveTaxAmount).getOrElse(0L),
        discountAmount = transaction.invoiceItem.map(_.totalDiscountAmount).getOrElse(0L),
        paidCreditGrantAmount = 0L,
        promotionalCreditGrantAmount = 0L,
        subtotal = transaction.invoiceItem.map(_.subtotal).getOrElse(0L),
        exclusiveTaxAmount = transaction.invoiceItem.map(_.totalExclusiveTaxAmount).getOrElse(0L),
        total = transaction.invoiceItem.map(_.total).getOrElse(0L),
      )
    )
    case RevRecTransaction.Type.UnbilledUsageSubscriptionItem => Seq(
      LineItem(
        id = transaction.subscriptionItem.map(_.base.id),
        description = transaction.subscriptionItem.flatMap(_.price).flatMap(_.product).map(_.name),
        principleAmount = 0L,
        startedAt = transaction.subscriptionItem.map(_.base.currentPeriodStart),
        endedAt = transaction.subscriptionItem.map(_.base.currentPeriodEnd),
        inclusiveTaxAmount = 0L,
        discountAmount = 0L,
        paidCreditGrantAmount = 0L,
        promotionalCreditGrantAmount = 0L,
        subtotal = 0L,
        exclusiveTaxAmount = 0L,
        total = 0L,
      )
    )
    case _ => Seq.empty
  }

  val usages: Seq[Usage] = transaction.base.tpe match {
    case RevRecTransaction.Type.Invoice => transaction.invoice.toList.flatMap(_.lineItems).flatMap { lineItem =>
      lineItem.meterEventSummaries.map { meterEventSummary =>
        Usage(
          description = lineItem.base.description,
          startedAt = meterEventSummary.startTime,
          endedAt = meterEventSummary.endTime,
          value = meterEventSummary.aggregatedValue
        )
      }
    }
    case RevRecTransaction.Type.UnbilledUsageSubscriptionItem => transaction.subscriptionItem.toList.flatMap(_.meterEventSummaries).map { meterEventSummary =>
      Usage(
        description = transaction.subscriptionItem.map(_.base.id),
        startedAt = meterEventSummary.startTime,
        endedAt = meterEventSummary.endTime,
        value = meterEventSummary.aggregatedValue
      )
    }
    case _ => Seq.empty
  }

  val billingActivities: Seq[BillingActivity.Value] = transaction.base.tpe match {
    case RevRecTransaction.Type.Invoice => transaction.invoice.toList.flatMap(_.billingActivities)
    case RevRecTransaction.Type.StandalonePaymentIntent => transaction.paymentIntent.toList.flatMap(_.billingActivities)
    case RevRecTransaction.Type.StandaloneCharge => transaction.charge.toList.flatMap(_.billingActivities)
    case RevRecTransaction.Type.UnbilledInvoiceItem => Seq.empty
    case RevRecTransaction.Type.UnbilledUsageSubscriptionItem => Seq.empty
    case RevRecTransaction.Type.StandaloneCustomerBalanceTransaction => transaction.customerBalanceTransaction.toList.flatMap(_.billingActivities)
    case RevRecTransaction.Type.StandaloneCreditBalanceTransaction => transaction.creditBalanceTransaction.toList.flatMap(_.billingActivities)
  }

  def toJson(): JsObject = Json.obj(
    "currency" -> currency,
    "total" -> total,
    "outstanding" -> outstanding,
    "paid" -> paid,
    "status" -> status.toString,
    "lineItems" -> lineItems.map(_.toJson()),
    "usages" -> usages.map(_.toJson()),
    "billingActivities" -> billingActivities.map(_.toJson())
  )
}

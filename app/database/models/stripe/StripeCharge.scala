package database.models.stripe

import database.models.stripe.RichStripeRefund
import framework.PostgresProfile.api.*
import framework.TransactionDetail.BillingActivity
import framework.TransactionDetail.BillingActivity.MakePayment
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class StripeCharge(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  balanceTransactionId: Option[String],
  customerId: Option[String],
  amount: Long,
  currency: String,
  description: Option[String],
  disputed: Boolean,
  refunded: Boolean,
  amountRefunded: Option[Long],
  paymentIntentId: Option[String],
  created: Instant,
  status: String,
  syncedAt: Instant
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "balanceTransactionId" -> balanceTransactionId,
    "customerId" -> customerId,
    "amount" -> amount,
    "currency" -> currency,
    "description" -> description,
    "disputed" -> disputed,
    "refunded" -> refunded,
    "amountRefunded" -> amountRefunded,
    "paymentIntentId" -> paymentIntentId,
    "created" -> created.toEpochMilli,
    "status" -> status,
  )
}

case class RichStripeCharge(
  base: StripeCharge,
  balanceTransaction: Option[StripeBalanceTransaction],
  disputes: Seq[RichStripeDispute],
  refunds: Seq[RichStripeRefund]
) extends Jsonable {
  lazy val syncedAt: Instant = Seq(Some(base.syncedAt), balanceTransaction.map(_.syncedAt), disputes.map(_.syncedAt), refunds.map(_.syncedAt)).flatten.max

  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "balanceTransaction" -> balanceTransaction.map(_.toJson()),
    "disputes" -> disputes.map(_.toJson()),
    "refunds" -> refunds.map(_.toJson()),
  )

  lazy val contraBillingActivities: Seq[BillingActivity.Value] = Seq(
    refunds.flatMap(_.billingActivities),
    disputes.flatMap(_.billingActivities)
  ).flatten.sortBy(_.timestamp)

  lazy val paymentBillingActivities: Seq[BillingActivity.Value] = Seq(
    balanceTransaction.toList.map { b =>
      MakePayment(
        timestamp = b.createdAt,
        chargeId = Some(base.id),
        paymentIntentId = base.paymentIntentId,
        paymentRecordId = None,
        amount = base.amount,
        currency = base.currency,
      )
    },
  ).flatten.sortBy(_.timestamp)

  lazy val billingActivities: Seq[BillingActivity.Value] = (paymentBillingActivities ++ contraBillingActivities).sortBy(_.timestamp)
}

class StripeChargeTable(tag: Tag) extends Table[StripeCharge](tag, Some("stripe"), "charge") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id")
  def balanceTransactionId: Rep[Option[String]] = column[Option[String]]("balance_transaction_id")
  def customerId: Rep[Option[String]] = column[Option[String]]("customer_id")
  def amount: Rep[Long] = column[Long]("amount")
  def currency: Rep[String] = column[String]("currency")
  def description: Rep[Option[String]] = column[Option[String]]("description")
  def disputed: Rep[Boolean] = column[Boolean]("disputed")
  def refunded: Rep[Boolean] = column[Boolean]("refunded")
  def amountRefunded: Rep[Option[Long]] = column[Option[Long]]("amount_refunded")
  def paymentIntentId: Rep[Option[String]] = column[Option[String]]("payment_intent_id")
  def created: Rep[Instant] = column[Instant]("created")
  def status: Rep[String] = column[String]("status")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[StripeCharge] = (
    stripeAccountId,
    liveMode,
    id,
    balanceTransactionId,
    customerId,
    amount,
    currency,
    description,
    disputed,
    refunded,
    amountRefunded,
    paymentIntentId,
    created,
    status,
    syncedAt
  ).<>((StripeCharge.apply _).tupled, StripeCharge.unapply)
}

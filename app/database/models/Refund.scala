package database.models

import framework.PostgresProfile.api.*
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import framework.TransactionDetail.BillingActivity
import framework.TransactionDetail.BillingActivity.{FailRefund, IssueRefund}
import slick.lifted.{ProvenShape, Rep}

case class Refund(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  balanceTransactionId: Option[String],
  failureBalanceTransactionId: Option[String],
  amount: Long,
  currency: String,
  chargeId: Option[String],
  paymentIntentId: Option[String],
  status: String,
  createdAt: Instant,
  syncedAt: Instant
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "amount" -> amount,
    "currency" -> currency,
    "chargeId" -> chargeId,
    "paymentIntentId" -> paymentIntentId,
    "status" -> status,
    "createdAt" -> createdAt.toEpochMilli,
  )
}

case class RichRefund(
  base: Refund,
  balanceTransaction: Option[BalanceTransaction],
  failureBalanceTransaction: Option[BalanceTransaction],
  belongsToCreditNote: Boolean
) extends Jsonable {
  lazy val syncedAt: Instant = Seq(Some(base.syncedAt), balanceTransaction.map(_.syncedAt), failureBalanceTransaction.map(_.syncedAt)).flatten.max

  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "belongsToCreditNote" -> belongsToCreditNote,
    "balanceTransaction" -> balanceTransaction.map(_.toJson()),
    "failureBalanceTransaction" -> failureBalanceTransaction.map(_.toJson()),
  )

  lazy val billingActivities: Seq[BillingActivity.Value] = Seq(
    balanceTransaction.map { b => IssueRefund(b.createdAt, base.id, base.amount, base.currency) }.toSeq,
    failureBalanceTransaction.map { b => FailRefund(b.createdAt, base.id, base.amount, base.currency) }.toSeq
  ).flatten.sortBy(_.timestamp)
}

class RefundTable(tag: Tag) extends Table[Refund](tag, "refund") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id")
  def balanceTransactionId: Rep[Option[String]] = column[Option[String]]("balance_transaction_id")
  def failureBalanceTransactionId: Rep[Option[String]] = column[Option[String]]("failure_balance_transaction_id")
  def amount: Rep[Long] = column[Long]("amount")
  def currency: Rep[String] = column[String]("currency")
  def chargeId: Rep[Option[String]] = column[Option[String]]("charge_id")
  def paymentIntentId: Rep[Option[String]] = column[Option[String]]("payment_intent_id")
  def status: Rep[String] = column[String]("status")
  def createdAt: Rep[Instant] = column[Instant]("created_at")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[Refund] = (
    stripeAccountId,
    liveMode,
    id,
    balanceTransactionId,
    failureBalanceTransactionId,
    amount,
    currency,
    chargeId,
    paymentIntentId,
    status,
    createdAt,
    syncedAt
  ).<>((Refund.apply _).tupled, Refund.unapply)
}

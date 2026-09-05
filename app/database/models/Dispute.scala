package database.models

import framework.TransactionDetail.BillingActivity
import framework.TransactionDetail.BillingActivity.WinDispute
import framework.PostgresProfile.api.*
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class Dispute(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  balanceTransactionIds: List[String],
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
    "balanceTransactionIds" -> balanceTransactionIds,
    "amount" -> amount,
    "currency" -> currency,
    "chargeId" -> chargeId,
    "paymentIntentId" -> paymentIntentId,
    "status" -> status,
    "createdAt" -> createdAt.toEpochMilli,
  )
}

case class RichDispute(
  base: Dispute,
  balanceTransactions: Seq[BalanceTransaction],
) extends Jsonable {
  lazy val syncedAt: Instant = Seq(Some(base.syncedAt), balanceTransactions.map(_.syncedAt).maxOption).flatten.max

  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "balanceTransactions" -> balanceTransactions.map(_.toJson()),
  )

  lazy val billingActivities: Seq[BillingActivity.Value] = balanceTransactions.map { b =>
    if (b.amount < 0) {
      BillingActivity.FileDispute(
        timestamp = b.createdAt,
        disputeId = base.id,
        amount = -base.amount,
        currency = base.currency,
      )
    } else {
      WinDispute(
        timestamp = b.createdAt,
        disputeId = base.id,
        amount = base.amount,
        currency = base.currency,
      )
    }
  }
}

class DisputeTable(tag: Tag) extends Table[Dispute](tag, "dispute") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id")
  def balanceTransactionIds: Rep[List[String]] = column[List[String]]("balance_transaction_ids")
  def amount: Rep[Long] = column[Long]("amount")
  def currency: Rep[String] = column[String]("currency")
  def chargeId: Rep[Option[String]] = column[Option[String]]("charge_id")
  def paymentIntentId: Rep[Option[String]] = column[Option[String]]("payment_intent_id")
  def status: Rep[String] = column[String]("status")
  def createdAt: Rep[Instant] = column[Instant]("created_at")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[Dispute] = (
    stripeAccountId,
    liveMode,
    id,
    balanceTransactionIds,
    amount,
    currency,
    chargeId,
    paymentIntentId,
    status,
    createdAt,
    syncedAt
  ).<>((Dispute.apply _).tupled, Dispute.unapply)
}

package database.models

import framework.PostgresProfile.api.*
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class BalanceTransaction(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  amount: Long,
  currency: String,
  description: String,
  feeAmount: Long,
  netAmount: Long,
  status: String,
  `type`: String,
  source: Option[String],
  createdAt: Instant,
  syncedAt: Instant
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
  )
}

case class RichBalanceTransaction(
  base: BalanceTransaction,
  charge: Option[Charge],
)

class BalanceTransactionTable(tag: Tag) extends Table[BalanceTransaction](tag, "balance_transaction") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id")
  def amount: Rep[Long] = column[Long]("amount")
  def currency: Rep[String] = column[String]("currency")
  def description: Rep[String] = column[String]("description")
  def feeAmount: Rep[Long] = column[Long]("fee_amount")
  def netAmount: Rep[Long] = column[Long]("net_amount")
  def status: Rep[String] = column[String]("status")
  def `type`: Rep[String] = column[String]("type")
  def source: Rep[Option[String]] = column[Option[String]]("source")
  def createdAt: Rep[Instant] = column[Instant]("created_at")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[BalanceTransaction] = (
    stripeAccountId,
    liveMode,
    id,
    amount,
    currency,
    description,
    feeAmount,
    netAmount,
    status,
    `type`,
    source,
    createdAt,
    syncedAt
  ).<>((BalanceTransaction.apply _).tupled, BalanceTransaction.unapply)
}

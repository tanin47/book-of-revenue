package database.models

import framework.{Instant, Jsonable}
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class CreditGrant(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  customer: String,
  amount: Option[Long],
  currency: Option[String],
  category: String,
  createdAt: Instant,
  effectiveAt: Instant,
  expiresAt: Option[Instant],
  voidedAt: Option[Instant]
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "customer" -> customer,
    "amount" -> amount,
    "currency" -> currency,
    "category" -> category,
    "createdAt" -> createdAt.toEpochMilli,
    "effectiveAt" -> effectiveAt.toEpochMilli,
    "expiresAt" -> expiresAt.map(_.toEpochMilli),
    "voidedAt" -> voidedAt.map(_.toEpochMilli),
  )
}

class CreditGrantTable(tag: Tag) extends Table[CreditGrant](tag, "credit_grant") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def customer: Rep[String] = column[String]("customer_id")
  def amount: Rep[Option[Long]] = column[Option[Long]]("amount")
  def currency: Rep[Option[String]] = column[Option[String]]("currency")
  def category: Rep[String] = column[String]("category")
  def createdAt: Rep[Instant] = column[Instant]("created_at")
  def effectiveAt: Rep[Instant] = column[Instant]("effective_at")
  def expiresAt: Rep[Option[Instant]] = column[Option[Instant]]("expires_at")
  def voidedAt: Rep[Option[Instant]] = column[Option[Instant]]("voided_at")

  def * : ProvenShape[CreditGrant] = (
    stripeAccountId,
    liveMode,
    id,
    customer,
    amount,
    currency,
    category,
    createdAt,
    effectiveAt,
    expiresAt,
    voidedAt
  ).<>((CreditGrant.apply _).tupled, CreditGrant.unapply)
}

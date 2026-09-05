package database.models

import framework.{Instant, Jsonable}
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class PriceTier(
  stripeAccountId: String,
  liveMode: Boolean,
  priceId: String,
  flatAmount: Option[Long],
  unitAmount: Option[Long],
  upTo: Option[Long],
  syncedAt: Instant,
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "flatAmount" -> flatAmount,
    "unitAmount" -> unitAmount,
    "upTo" -> upTo,
  )
}

class PriceTierTable(tag: Tag) extends Table[PriceTier](tag, "price_tier") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def priceId: Rep[String] = column[String]("price_id")
  def flatAmount: Rep[Option[Long]] = column[Option[Long]]("flat_amount")
  def unitAmount: Rep[Option[Long]] = column[Option[Long]]("unit_amount")
  def upTo: Rep[Option[Long]] = column[Option[Long]]("up_to")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[PriceTier] = (
    stripeAccountId,
    liveMode,
    priceId,
    flatAmount,
    unitAmount,
    upTo,
    syncedAt
  ).<>((PriceTier.apply _).tupled, PriceTier.unapply)
}

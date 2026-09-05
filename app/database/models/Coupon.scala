package database.models

import framework.Jsonable
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class Coupon(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  amountOff: Option[Long],
  currency: Option[String],
  percentOff: Option[Double],
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "amountOff" -> amountOff,
    "currency" -> currency,
    "percentOff" -> percentOff,
  )
}

class CouponTable(tag: Tag) extends Table[Coupon](tag, "coupon") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def amountOff: Rep[Option[Long]] = column[Option[Long]]("amount_off")
  def currency: Rep[Option[String]] = column[Option[String]]("currency")
  def percentOff: Rep[Option[Double]] = column[Option[Double]]("percent_off")

  def * : ProvenShape[Coupon] = (
    stripeAccountId,
    liveMode,
    id,
    amountOff,
    currency,
    percentOff
  ).<>((Coupon.apply _).tupled, Coupon.unapply)
}

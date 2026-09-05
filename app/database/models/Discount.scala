package database.models

import framework.Jsonable
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class Discount(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  couponId: Option[String]
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "couponId" -> couponId,
  )
}

case class RichDiscount(
  base: Discount,
  coupon: Option[Coupon]
) extends Jsonable {
  def computeDiscount(amount: Long): Long = {
    coupon.flatMap { coupon => coupon.amountOff.orElse(coupon.percentOff.map { p => (p * amount / 100).toLong }) }.getOrElse(0)
  }

  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "coupon" -> coupon.map(_.toJson()),
  )
}

class DiscountTable(tag: Tag) extends Table[Discount](tag, "discount") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def couponId: Rep[Option[String]] = column[Option[String]]("coupon_id")

  def * : ProvenShape[Discount] = (
    stripeAccountId,
    liveMode,
    id,
    couponId
  ).<>((Discount.apply _).tupled, Discount.unapply)
}

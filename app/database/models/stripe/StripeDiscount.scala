package database.models.stripe

import framework.Jsonable
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class StripeDiscount(
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

case class RichStripeDiscount(
  base: StripeDiscount,
  coupon: Option[StripeCoupon]
) extends Jsonable {
  def computeDiscount(amount: Long): Long = {
    coupon.flatMap { coupon => coupon.amountOff.orElse(coupon.percentOff.map { p => (p * amount / 100).toLong }) }.getOrElse(0)
  }

  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "coupon" -> coupon.map(_.toJson()),
  )
}

class StripeDiscountTable(tag: Tag) extends Table[StripeDiscount](tag, Some("stripe"), "discount") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def couponId: Rep[Option[String]] = column[Option[String]]("coupon_id")

  def * : ProvenShape[StripeDiscount] = (
    stripeAccountId,
    liveMode,
    id,
    couponId
  ).<>((StripeDiscount.apply _).tupled, StripeDiscount.unapply)
}

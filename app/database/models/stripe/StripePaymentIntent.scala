package database.models.stripe

import framework.PostgresProfile.api.*
import framework.TransactionDetail.BillingActivity
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class StripePaymentIntent(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  customerId: Option[String],
  amount: Long,
  currency: String,
  description: Option[String],
  latestChargeId: Option[String],
  syncedAt: Instant
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "customerId" -> customerId,
    "amount" -> amount,
    "currency" -> currency,
    "description" -> description,
    "latestChargeId" -> latestChargeId,
  )
}

case class RichStripePaymentIntent(
  base: StripePaymentIntent,
  charge: Option[RichStripeCharge]
) extends Jsonable {
  lazy val syncedAt: Instant = Seq(Some(base.syncedAt), charge.map(_.syncedAt)).flatten.max

  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "charge" -> charge.map(_.toJson()),
  )
  lazy val contraBillingActivities: Seq[BillingActivity.Value] = charge.toList.flatMap(_.contraBillingActivities)

  lazy val paymentBillingActivities: Seq[BillingActivity.Value] = charge.toList.flatMap(_.paymentBillingActivities)

  lazy val billingActivities: Seq[BillingActivity.Value] = (paymentBillingActivities ++ contraBillingActivities).sortBy(_.timestamp)
}

class StripePaymentIntentTable(tag: Tag) extends Table[StripePaymentIntent](tag, Some("stripe"), "payment_intent") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id")
  def customerId: Rep[Option[String]] = column[Option[String]]("customer_id")
  def amount: Rep[Long] = column[Long]("amount")
  def currency: Rep[String] = column[String]("currency")
  def description: Rep[Option[String]] = column[Option[String]]("description")
  def latestChargeId: Rep[Option[String]] = column[Option[String]]("latest_charge_id")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[StripePaymentIntent] = (
    stripeAccountId,
    liveMode,
    id,
    customerId,
    amount,
    currency,
    description,
    latestChargeId,
    syncedAt
  ).<>((StripePaymentIntent.apply _).tupled, StripePaymentIntent.unapply)
}

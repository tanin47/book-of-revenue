package database.models

import framework.PostgresProfile.api.*
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class Subscription(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  customerId: String,
  currency: String,
  status: String,
  startDate: Instant,
  discountIds: List[String],
  defaultTaxRateIds: List[String],
  syncedAt: Instant,
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "customerId" -> customerId,
    "currency" -> currency,
    "status" -> status,
    "startDate" -> startDate.toEpochMilli,
    "discountIds" -> discountIds,
    "defaultTaxRateIds" -> defaultTaxRateIds,
  )
}

case class RichSubscription(
  base: Subscription,
  discounts: Seq[RichDiscount],
  defaultTaxRates: Seq[TaxRate],
) extends Jsonable {
  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "discounts" -> discounts.map(_.toJson()),
    "defaultTaxRates" -> defaultTaxRates.map(_.toJson()),
  )
}

class SubscriptionTable(tag: Tag) extends Table[Subscription](tag, "subscription") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def customerId: Rep[String] = column[String]("customer_id")
  def currency: Rep[String] = column[String]("currency")
  def status: Rep[String] = column[String]("status")
  def startDate: Rep[Instant] = column[Instant]("start_date")
  def discountIds: Rep[List[String]] = column[List[String]]("discount_ids")
  def defaultTaxRateIds: Rep[List[String]] = column[List[String]]("default_tax_rate_ids")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[Subscription] = (
    stripeAccountId,
    liveMode,
    id,
    customerId,
    currency,
    status,
    startDate,
    discountIds,
    defaultTaxRateIds,
    syncedAt
  ).<>((Subscription.apply _).tupled, Subscription.unapply)
}

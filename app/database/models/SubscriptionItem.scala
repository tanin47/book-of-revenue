package database.models

import framework.PostgresProfile.api.*
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import services.ExchangeRate
import slick.lifted.{ProvenShape, Rep}

case class SubscriptionItem(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  subscriptionId: String,
  priceId: String,
  quantity: Long,
  currentPeriodEnd: Instant,
  currentPeriodStart: Instant,
  discountIds: List[String],
  taxRateIds: List[String],
  syncedAt: Instant,
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "subscriptionId" -> subscriptionId,
    "priceId" -> priceId,
    "quantity" -> quantity,
    "currentPeriodEnd" -> currentPeriodEnd.toEpochMilli,
    "currentPeriodStart" -> currentPeriodStart.toEpochMilli,
    "discountIds" -> discountIds,
    "taxRateIds" -> taxRateIds,
  )
}

case class RichSubscriptionItem(
  base: SubscriptionItem,
  subscription: RichSubscription,
  price: Option[RichPrice],
  meterEventSummaries: Seq[MeterEventSummary],
  discounts: Seq[RichDiscount],
  taxRates: Seq[TaxRate],
  currentPeriodStartExchangeRate: ExchangeRate
) extends Jsonable {
  lazy val syncedAt: Instant = Seq(Some(subscription.base.syncedAt), Some(base.syncedAt), price.map(_.base.syncedAt), meterEventSummaries.map(_.syncedAt).maxOption).flatten.max

  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "subscription" -> subscription.toJson(),
    "price" -> price.map(_.toJson()),
    "meterEventSummaries" -> meterEventSummaries.map(_.toJson()),
    "discounts" -> discounts.map(_.toJson()),
    "taxRates" -> taxRates.map(_.toJson()),
  )
}

class SubscriptionItemTable(tag: Tag) extends Table[SubscriptionItem](tag, "subscription_item") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def subscriptionId: Rep[String] = column[String]("subscription_id")
  def priceId: Rep[String] = column[String]("price_id")
  def quantity: Rep[Long] = column[Long]("quantity")
  def currentPeriodEnd: Rep[Instant] = column[Instant]("current_period_end")
  def currentPeriodStart: Rep[Instant] = column[Instant]("current_period_start")
  def discountIds: Rep[List[String]] = column[List[String]]("discount_ids")
  def taxRateIds: Rep[List[String]] = column[List[String]]("tax_rate_ids")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[SubscriptionItem] = (
    stripeAccountId,
    liveMode,
    id,
    subscriptionId,
    priceId,
    quantity,
    currentPeriodEnd,
    currentPeriodStart,
    discountIds,
    taxRateIds,
    syncedAt
  ).<>((SubscriptionItem.apply _).tupled, SubscriptionItem.unapply)
}

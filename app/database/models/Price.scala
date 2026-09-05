package database.models

import framework.PostgresProfile.api.*
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class Price(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  currency: String,
  productId: String,
  `type`: String,
  billingScheme: String,
  unitAmount: Long,
  tiersMode: Option[String],
  recurringInterval: Option[String],
  recurringIntervalCount: Option[Int],
  recurringMeterId: Option[String],
  recurringUsageType: Option[String],
  syncedAt: Instant,
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "currency" -> currency,
    "productId" -> productId,
    "type" -> `type`,
    "billingScheme" -> billingScheme,
    "unitAmount" -> unitAmount,
    "tiersMode" -> tiersMode,
    "recurringInterval" -> recurringInterval,
    "recurringIntervalCount" -> recurringIntervalCount,
    "recurringMeterId" -> recurringMeterId,
    "recurringUsageType" -> recurringUsageType,
  )
}

case class RichPrice(
  base: Price,
  product: Option[Product],
  tiers: Seq[PriceTier]
) extends Jsonable {
  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "tiers" -> tiers.map(_.toJson()),
    "product" -> product.map(_.toJson())
  )
}

class PriceTable(tag: Tag) extends Table[Price](tag, "price") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def currency: Rep[String] = column[String]("currency")
  def productId: Rep[String] = column[String]("product_id")
  def `type`: Rep[String] = column[String]("type")
  def billingScheme: Rep[String] = column[String]("billing_scheme")
  def unitAmount: Rep[Long] = column[Long]("unit_amount")
  def tiersMode: Rep[Option[String]] = column[Option[String]]("tiers_mode")
  def recurringInterval: Rep[Option[String]] = column[Option[String]]("recurring_interval")
  def recurringIntervalCount: Rep[Option[Int]] = column[Option[Int]]("recurring_interval_count")
  def recurringMeterId: Rep[Option[String]] = column[Option[String]]("recurring_meter_id")
  def recurringUsageType: Rep[Option[String]] = column[Option[String]]("recurring_usage_type")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[Price] = (
    stripeAccountId,
    liveMode,
    id,
    currency,
    productId,
    `type`,
    billingScheme,
    unitAmount,
    tiersMode,
    recurringInterval,
    recurringIntervalCount,
    recurringMeterId,
    recurringUsageType,
    syncedAt
  ).<>((Price.apply _).tupled, Price.unapply)
}

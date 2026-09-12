package database.models.stripe

import framework.PostgresProfile.api.*
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class StripeProduct(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  name: String,
  description: Option[String],
  syncedAt: Instant
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "name" -> name,
    "description" -> description,
  )
}

class StripeProductTable(tag: Tag) extends Table[StripeProduct](tag, Some("stripe"), "product") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def name: Rep[String] = column[String]("name")
  def description: Rep[Option[String]] = column[Option[String]]("description")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[StripeProduct] = (
    stripeAccountId,
    liveMode,
    id,
    name,
    description,
    syncedAt
  ).<>((StripeProduct.apply _).tupled, StripeProduct.unapply)
}

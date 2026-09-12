package database.models.stripe

import framework.PostgresProfile.api.*
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class StripeCustomer(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  name: Option[String],
  email: Option[String],
  syncedAt: Instant
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "name" -> name,
    "email" -> email,
  )
}

class StripeCustomerTable(tag: Tag) extends Table[StripeCustomer](tag, Some("stripe"), "customer") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def name: Rep[Option[String]] = column[Option[String]]("name")
  def email: Rep[Option[String]] = column[Option[String]]("email")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[StripeCustomer] = (
    stripeAccountId,
    liveMode,
    id,
    name,
    email,
    syncedAt
  ).<>((StripeCustomer.apply _).tupled, StripeCustomer.unapply)
}

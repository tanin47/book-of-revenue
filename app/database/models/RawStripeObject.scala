package database.models

import framework.{Instant, Jsonable}
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class RawStripeObject(
  id: String,
  stripeAccountId: String,
  liveMode: Boolean,
  objectType: String,
  checksum: String,
  rawJson: String,
  syncedAt: Instant,
  processedCount: Int
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "rawJson" -> rawJson,
  )
}

class RawStripeObjectTable(tag: Tag) extends Table[RawStripeObject](tag, "raw_stripe_object") {
  def id: Rep[String] = column[String]("id")
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def objectType: Rep[String] = column[String]("object_type")
  def checksum: Rep[String] = column[String]("checksum")
  def rawJson: Rep[String] = column[String]("raw_json")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")
  def processedCount: Rep[Int] = column[Int]("processed_count")

  def * : ProvenShape[RawStripeObject] = (
    id,
    stripeAccountId,
    liveMode,
    objectType,
    checksum,
    rawJson,
    syncedAt,
    processedCount
  ).<>((RawStripeObject.apply _).tupled, RawStripeObject.unapply)
}

package database.models

import framework.PostgresProfile.api.*
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class MeterEventSummary(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  aggregatedValue: Long,
  meterId: String,
  customerId: String,
  startTime: Instant,
  endTime: Instant,
  syncedAt: Instant,
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "aggregatedValue" -> aggregatedValue,
    "meterId" -> meterId,
    "customerId" -> customerId,
    "startTime" -> startTime.toEpochMilli,
    "endTime" -> endTime.toEpochMilli,
  )
}

class MeterEventSummaryTable(tag: Tag) extends Table[MeterEventSummary](tag, "meter_event_summary") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def aggregatedValue: Rep[Long] = column[Long]("aggregated_value")
  def meterId: Rep[String] = column[String]("meter_id")
  def customerId: Rep[String] = column[String]("customer_id")
  def startTime: Rep[Instant] = column[Instant]("start_time")
  def endTime: Rep[Instant] = column[Instant]("end_time")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[MeterEventSummary] = (
    stripeAccountId,
    liveMode,
    id,
    aggregatedValue,
    meterId,
    customerId,
    startTime,
    endTime,
    syncedAt
  ).<>((MeterEventSummary.apply _).tupled, MeterEventSummary.unapply)
}

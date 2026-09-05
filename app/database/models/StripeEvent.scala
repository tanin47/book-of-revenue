package database.models

import framework.Instant
import framework.PostgresProfile.api.*
import slick.lifted.{ProvenShape, Rep}

case class StripeEvent(
  id: String,
  stripeAccountId: String,
  liveMode: Boolean,
  rawJson: String,
  processedCount: Int,
  createdAt: Instant,
)

class StripeEventTable(tag: Tag) extends Table[StripeEvent](tag, "stripe_event") {
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def rawJson: Rep[String] = column[String]("raw_json")
  def processedCount: Rep[Int] = column[Int]("processed_count")
  def createdAt: Rep[Instant] = column[Instant]("created_at")

  def * : ProvenShape[StripeEvent] = (
    id,
    stripeAccountId,
    liveMode,
    rawJson,
    processedCount,
    createdAt
  ).<>((StripeEvent.apply _).tupled, StripeEvent.unapply)
}

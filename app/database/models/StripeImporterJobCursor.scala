package database.models

import framework.PostgresProfile.api.*
import slick.lifted.{ProvenShape, Rep}

object StripeImporterJobCursor {
  enum Mode extends Enum[Mode] {
    case StartingAfter, EndingBefore
  }
}

case class StripeImporterJobCursor(
  stripeImporterJobId: String,
  objectType: String,
  customerId: Option[String],
  latestId: Option[String],
  startingAfter: Option[String],
  endingBefore: Option[String],
) {
  lazy val mode: StripeImporterJobCursor.Mode = {
    if (latestId.isEmpty || startingAfter.isDefined) {
      StripeImporterJobCursor.Mode.StartingAfter
    } else {
      StripeImporterJobCursor.Mode.EndingBefore
    }
  }
}

class StripeImporterJobCursorTable(tag: Tag) extends Table[StripeImporterJobCursor](tag, "stripe_importer_job_cursor") {
  def stripeImporterJobId: Rep[String] = column[String]("stripe_importer_job_id")
  def objectType: Rep[String] = column[String]("object_type")
  def customerId: Rep[Option[String]] = column[Option[String]]("customer_id")
  def latestId: Rep[Option[String]] = column[Option[String]]("latest_id")
  def startingAfter: Rep[Option[String]] = column[Option[String]]("starting_after")
  def endingBefore: Rep[Option[String]] = column[Option[String]]("ending_before")

  def * : ProvenShape[StripeImporterJobCursor] = (
    stripeImporterJobId,
    objectType,
    customerId,
    latestId,
    startingAfter,
    endingBefore
  ).<>((StripeImporterJobCursor.apply _).tupled, StripeImporterJobCursor.unapply)
}

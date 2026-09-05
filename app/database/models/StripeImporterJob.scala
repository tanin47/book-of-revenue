package database.models

import framework.Instant
import framework.PostgresProfile.api.*
import slick.lifted.{ProvenShape, Rep}

object StripeImporterJob {
  enum Status extends Enum[Status] {
    case Active, ManuallyEnded, Finished
  }
  enum JobType extends Enum[JobType] {
    case Batch, Event
  }
}

case class StripeImporterJob(
  id: String,
  stripeAccountId: String,
  liveMode: Boolean,
  startedAt: Option[Instant],
  finishedAt: Option[Instant],
  jobType: StripeImporterJob.JobType,
  status: StripeImporterJob.Status
)

class StripeImporterJobTable(tag: Tag) extends Table[StripeImporterJob](tag, "stripe_importer_job") {
  def id: Rep[String] = column[String]("id", O.PrimaryKey, O.AutoInc)
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def startedAt: Rep[Option[Instant]] = column[Option[Instant]]("started_at")
  def finishedAt: Rep[Option[Instant]] = column[Option[Instant]]("finished_at")
  def jobType: Rep[StripeImporterJob.JobType] = column[StripeImporterJob.JobType]("job_type")
  def status: Rep[StripeImporterJob.Status] = column[StripeImporterJob.Status]("status")

  def * : ProvenShape[StripeImporterJob] = (
    id,
    stripeAccountId,
    liveMode,
    startedAt,
    finishedAt,
    jobType,
    status
  ).<>((StripeImporterJob.apply _).tupled, StripeImporterJob.unapply)
}

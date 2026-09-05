package database.services

import database.models.{StripeImporterJob, StripeImporterJobTable}
import framework.{Instant, PlayConfig}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class StripeImporterJobService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import framework.PostgresProfile.api.*

  val query: TableQuery[StripeImporterJobTable] = TableQuery[StripeImporterJobTable]

  def create(
    jobType: StripeImporterJob.JobType,
    stripeAccountId: String,
    liveMode: Boolean
  ): Future[StripeImporterJob] = {
    val entity = StripeImporterJob(
      id = "",
      stripeAccountId = stripeAccountId,
      liveMode = liveMode,
      jobType = jobType,
      startedAt = Some(Instant.now()),
      finishedAt = None,
      status = StripeImporterJob.Status.Active
    )

    for {
      id <- db.run {
        (query returning query.map(_.id)) += entity
      }
    } yield {
      entity.copy(id = id)
    }
  }

  def getById(id: String): Future[Option[StripeImporterJob]] = {
    db.run(query.filter(_.id === id).result.headOption)
  }

  def getActive(
    jobType: StripeImporterJob.JobType,
    stripeAccountId: String,
    liveMode: Boolean
  ): Future[Option[StripeImporterJob]] = {
    db.run {
      query
        .filter { q =>
          q.status === StripeImporterJob.Status.Active &&
            q.jobType === jobType &&
            q.stripeAccountId === stripeAccountId &&
            q.liveMode === liveMode
        }
        .result
        .headOption
    }
  }
}

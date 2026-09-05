package database.services

import database.models.{StripeImporterJobCursor, StripeImporterJobCursorTable}
import framework.PlayConfig
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object StripeImporterJobCursorService {
  case class CreateData(
    stripeImporterJobId: String,
    objectType: String,
    customerId: Option[String],
  )
}

@Singleton
class StripeImporterJobCursorService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import StripeImporterJobCursorService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[StripeImporterJobCursorTable] = TableQuery[StripeImporterJobCursorTable]

  def create(data: CreateData): Future[StripeImporterJobCursor] = {
    val entity = StripeImporterJobCursor(
      stripeImporterJobId = data.stripeImporterJobId,
      objectType = data.objectType,
      customerId = data.customerId,
      latestId = None,
      startingAfter = None,
      endingBefore = None
    )

    db.run(query += entity).map(_ => entity)
  }

  def get(
    jobId: String,
    objectType: String,
    customerId: Option[String]
  ): Future[Option[StripeImporterJobCursor]] = {
    db.run {
      query
        .filter { q =>
          val customerIdCond = customerId.map(q.customerId === _).getOrElse(LiteralColumn(true).?)
          q.stripeImporterJobId === jobId && q.objectType === objectType && customerIdCond
        }
        .result
        .headOption
    }
  }

  def update(
    jobId: String,
    objectType: String,
    customerId: Option[String],
    latestId: Option[String],
    startingAfter: Option[String],
    endingBefore: Option[String],
  ): Future[Unit] = {
    db
      .run {
        query
          .filter { q =>
            val customerIdCond = customerId.map(q.customerId === _).getOrElse(LiteralColumn(true).?)
            q.stripeImporterJobId === jobId && q.objectType === objectType && customerIdCond
          }
          .map { q => (q.latestId, q.startingAfter, q.endingBefore) }
          .update((latestId, startingAfter, endingBefore))
      }
      .map { _ => () }
  }
}

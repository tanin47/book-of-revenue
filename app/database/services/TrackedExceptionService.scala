package database.services

import database.models.{TrackedException, TrackedExceptionTable}
import framework.{BaseDbService, Instant}
import play.api.db.slick.DatabaseConfigProvider
import slick.lifted.TableQuery

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object TrackedExceptionService {
  case class CreateData(
    exceptionClass: String,
    message: String,
    stackTrace: String,
    createdAt: Instant
  )
}

@Singleton
class TrackedExceptionService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
    extends BaseDbService {

  import TrackedExceptionService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[TrackedExceptionTable] = TableQuery[TrackedExceptionTable]

  def create(e: Throwable): Future[TrackedException] = {
    create(CreateData(
      exceptionClass = e.getClass.getCanonicalName,
      message = e.getMessage,
      stackTrace = e.getStackTrace.mkString("\n"),
      createdAt = Instant.now()
    ))
  }

  def create(data: CreateData): Future[TrackedException] = {
    val entity = TrackedException(
      createdAt = data.createdAt,
      exceptionClass = data.exceptionClass,
      message = data.message,
      stackTrace = data.stackTrace
    )

    db
      .run { query += entity }
      .map { _ => entity }
  }

  def getAll(limit: Int): Future[Seq[TrackedException]] = {
    db.run {
      query.sortBy(_.createdAt.desc).take(limit).result
    }
  }
}

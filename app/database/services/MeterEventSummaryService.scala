package database.services

import database.models.stripe.{StripeMeterEventSummary, StripeMeterEventSummaryTable}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.JdbcProfile

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object MeterEventSummaryService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    aggregatedValue: Long,
    meterId: String,
    customerId: String,
    startTime: Instant,
    endTime: Instant,
    syncedAt: Instant,
  )
}

@Singleton
class MeterEventSummaryService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import MeterEventSummaryService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[StripeMeterEventSummaryTable] = TableQuery[StripeMeterEventSummaryTable]

  def create(data: CreateData): Future[StripeMeterEventSummary] = {
    val entity = StripeMeterEventSummary(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      aggregatedValue = data.aggregatedValue,
      meterId = data.meterId,
      customerId = data.customerId,
      startTime = data.startTime,
      endTime = data.endTime,
      syncedAt = data.syncedAt
    )

    for {
      existing <- getById(entity.id)
      _ <- existing match {
        case Some(_) => update(entity)
        case None =>
          db
            .run { query += entity }
            .recoverWith {
              case e: PSQLException if matchUniqueConstraintException(e, "meter_event_summary_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: StripeMeterEventSummary): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getById(id: String): Future[Option[StripeMeterEventSummary]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def getAll(): Future[Seq[StripeMeterEventSummary]] = {
    db.run {
      query.result
    }
  }

  def getByMeterIds(meterIds: Set[String]): Future[Seq[StripeMeterEventSummary]] = {
    db.run {
      query.filter(_.meterId.inSet(meterIds)).result
    }
  }

  def getByMeterIdAndCustomerId(meterId: String, customerId: String): Future[Seq[StripeMeterEventSummary]] = {
    db.run {
      query.filter { q => q.meterId === meterId && q.customerId === customerId }.result
    }
  }
}

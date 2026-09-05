package database.services

import database.models.{StripeEvent, StripeEventTable}
import framework.{Instant, PlayConfig}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object StripeEventService {
  case class CreateData(
    id: String,
    stripeAccountId: String,
    liveMode: Boolean,
    rawJson: String,
    createdAt: Instant
  )
}

@Singleton
class StripeEventService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import StripeEventService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[StripeEventTable] = TableQuery[StripeEventTable]

  def create(items: Seq[CreateData]): Future[Seq[StripeEvent]] = {
    val entities = items.map { item =>
      StripeEvent(
        id = item.id,
        stripeAccountId = item.stripeAccountId,
        liveMode = item.liveMode,
        rawJson = item.rawJson,
        processedCount = 0,
        createdAt = item.createdAt
      )
    }

    val action = SimpleDBIO[Unit] { ctx =>
      val conn = ctx.connection
      val stmt = conn.prepareStatement("INSERT INTO stripe_event (id, stripe_account_id, live_mode, raw_json, processed_count, created_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING")

      entities.foreach { entity =>
        stmt.setString(1, entity.id)
        stmt.setString(2, entity.stripeAccountId)
        stmt.setBoolean(3, entity.liveMode)
        stmt.setString(4, entity.rawJson)
        stmt.setInt(5, entity.processedCount)
        stmt.setTimestamp(6, new Timestamp(entity.createdAt.toEpochMilli))
        stmt.addBatch()
      }

      stmt.executeBatch()
      stmt.close()
    }

    for {
      _ <- db.run(action)
    } yield {
      entities
    }
  }

  def getUnprocessed(exclusiveMaxCreatedAt: Option[Instant], exclusiveMaxId: Option[String]): Future[Seq[StripeEvent]] = {
    db.run {
      query
        .filter { q =>
          val createdAtCond = exclusiveMaxCreatedAt.map(q.createdAt < _).getOrElse(LiteralColumn(true)).?
          val idCond = exclusiveMaxId.map(q.id < _).getOrElse(LiteralColumn(true))
          q.processedCount === 0 && createdAtCond && idCond
        }
        .sortBy { q => (q.createdAt.desc, q.id.desc) }
        .take(100)
        .result
    }
  }

  def getById(id: String): Future[Option[StripeEvent]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def incrementProcessedCount(id: String): Future[Unit] = {
    db
      .run { sqlu"UPDATE stripe_event SET processed_count = processed_count + 1 WHERE id = $id" }
      .map { _ => () }
  }
}

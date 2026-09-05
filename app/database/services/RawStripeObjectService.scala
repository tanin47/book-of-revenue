package database.services

import database.models.{RawStripeObject, RawStripeObjectTable}
import framework.{Instant, PlayConfig}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import java.math.BigInteger
import java.security.MessageDigest
import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object RawStripeObjectService {
  case class CreateData(
    id: String,
    stripeAccountId: String,
    liveMode: Boolean,
    objectType: String,
    rawJson: String,
    syncedAt: Instant = Instant.now()
  )

  def computeChecksum(rawJson: String): String = {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(rawJson.getBytes("UTF-8"))
    val bigInt = new BigInteger(1, digest)
    val hashText = bigInt.toString(16)
    String.format("%32s", hashText).replace(' ', '0')
  }

  case class Stats(
    count: Long,
    maxUpdatedAt: Option[Instant],
  )
}

@Singleton
class RawStripeObjectService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import RawStripeObjectService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[RawStripeObjectTable] = TableQuery[RawStripeObjectTable]

  def create(items: Seq[CreateData]): Future[Seq[RawStripeObject]] = {
    val entities = items.map { item =>
      RawStripeObject(
        id = item.id,
        stripeAccountId = item.stripeAccountId,
        liveMode = item.liveMode,
        objectType = item.objectType,
        checksum = computeChecksum(item.rawJson),
        rawJson = item.rawJson,
        syncedAt = item.syncedAt,
        processedCount = 0
      )
    }

    val action = SimpleDBIO[Unit] { ctx =>
      val conn = ctx.connection
      val stmt = conn.prepareStatement("INSERT INTO raw_stripe_object (id, stripe_account_id, live_mode, object_type, checksum, raw_json, synced_at, processed_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id, checksum) DO NOTHING")

      entities.foreach { entity =>
        stmt.setString(1, entity.id)
        stmt.setString(2, entity.stripeAccountId)
        stmt.setBoolean(3, entity.liveMode)
        stmt.setString(4, entity.objectType)
        stmt.setString(5, entity.checksum)
        stmt.setString(6, entity.rawJson)
        stmt.setTimestamp(7, new Timestamp(entity.syncedAt.toEpochMilli))
        stmt.setInt(8, entity.processedCount)
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

  def getObjects(
    stripeAccountId: String,
    liveMode: Boolean,
    objectType: String,
    exclusiveMaxCustomerId: Option[String]
  ): Future[Seq[RawStripeObject]] = {
    db.run {
      query
        .filter { q =>
          q.stripeAccountId === stripeAccountId &&
            q.liveMode === liveMode &&
            q.objectType === objectType &&
            exclusiveMaxCustomerId.map(q.id < _).getOrElse(LiteralColumn(true))
        }
        .sortBy(_.id.desc)
        .take(100)
        .result
    }
  }

  def getUnprocessed(exclusiveMaxSyncedAt: Option[Instant], exclusiveMaxId: Option[String]): Future[Seq[RawStripeObject]] = {
    db.run {
      query
        .filter { q =>
          val syncedAtCond = exclusiveMaxSyncedAt
            .map { s =>
              val idCond = exclusiveMaxId.map(q.id < _).getOrElse(LiteralColumn(false))
              q.syncedAt < s || (q.syncedAt === s && idCond)
            }
            .getOrElse(LiteralColumn(true)).?
          q.processedCount === 0 && syncedAtCond
        }
        .sortBy { q => (q.syncedAt.desc, q.id.desc) }
        .take(100)
        .result
    }
  }

  def incrementProcessedCount(id: String, checksum: String): Future[Unit] = {
    db
      .run { sqlu"UPDATE raw_stripe_object SET processed_count = processed_count + 1 WHERE id = $id AND checksum = $checksum" }
      .map { _ => () }
  }

  def getStats(stripeAccountId: String, liveMode: Boolean, onlyProcessed: Boolean): Future[Stats] = {
    val processCountCond = if (onlyProcessed) {
      sql"AND processed_count > 0"
    } else {
      sql""
    }
    db.run {
      makeSql(
        sql"""
          SELECT COUNT(*) AS count, MAX(synced_at) AS max_synced_at
          FROM raw_stripe_object
          WHERE stripe_account_id = $stripeAccountId AND live_mode = $liveMode
        """,
        processCountCond
      ).as[(Option[Long], Option[Instant])]
    }
      .map { items =>
        items
          .headOption
          .map { case (count, maxUpdatedAt) => Stats(count.getOrElse(0L), maxUpdatedAt) }
          .getOrElse(Stats(0L, None))
      }
  }
}

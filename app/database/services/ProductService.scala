package database.services

import database.models.{Product, ProductTable}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object ProductService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    name: String,
    description: Option[String],
    syncedAt: Instant
  )
}

@Singleton
class ProductService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import ProductService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[ProductTable] = TableQuery[ProductTable]

  def create(data: CreateData): Future[Product] = {
    val entity = Product(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      name = data.name,
      description = data.description,
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
              case e: PSQLException if matchUniqueConstraintException(e, "product_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: Product): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getAll(): Future[Seq[Product]] = {
    db.run {
      query.result
    }
  }

  def getById(id: String): Future[Option[Product]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[Product]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }
}

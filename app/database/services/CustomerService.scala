package database.services

import database.models.{Customer, CustomerTable}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object CustomerService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    name: Option[String],
    email: Option[String],
    syncedAt: Instant
  )
}

@Singleton
class CustomerService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import CustomerService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[CustomerTable] = TableQuery[CustomerTable]

  def create(data: CreateData): Future[Customer] = {
    val entity = Customer(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      name = data.name,
      email = data.email,
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
              case e: PSQLException if matchUniqueConstraintException(e, "customer_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: Customer): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getAll(): Future[Seq[Customer]] = {
    db.run {
      query.result
    }
  }

  def getById(stripeAccountId: String, liveMode: Boolean, id: String): Future[Option[Customer]] = {
    db.run {
      query.filter { q => q.id === id && q.stripeAccountId === stripeAccountId && q.liveMode === liveMode }.result.headOption
    }
  }

  def getById(id: String): Future[Option[Customer]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[Customer]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }
}

package database.services

import database.models.stripe.{StripeCreditGrant, StripeCreditGrantTable}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object CreditGrantService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    customer: String,
    amount: Option[Long],
    currency: Option[String],
    category: String,
    createdAt: Instant,
    effectiveAt: Instant,
    expiresAt: Option[Instant],
    voidedAt: Option[Instant]
  )
}

@Singleton
class CreditGrantService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import CreditGrantService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[StripeCreditGrantTable] = TableQuery[StripeCreditGrantTable]

  def create(data: CreateData): Future[StripeCreditGrant] = {
    val entity = StripeCreditGrant(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      customer = data.customer,
      amount = data.amount,
      currency = data.currency,
      category = data.category,
      createdAt = data.createdAt,
      effectiveAt = data.effectiveAt,
      expiresAt = data.expiresAt,
      voidedAt = data.voidedAt
    )

    for {
      existing <- getById(entity.id)
      _ <- existing match {
        case Some(_) => update(entity)
        case None =>
          db
            .run { query += entity }
            .recoverWith {
              case e: PSQLException if matchUniqueConstraintException(e, "credit_grant_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: StripeCreditGrant): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getById(id: String): Future[Option[StripeCreditGrant]] = {
    getByIds(Set(id)).map(_.headOption)
  }

  def getAll(): Future[Seq[StripeCreditGrant]] = {
    db.run {
      query.result
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[StripeCreditGrant]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }
}

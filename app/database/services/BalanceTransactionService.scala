package database.services

import database.models.stripe.{StripeBalanceTransaction, StripeBalanceTransactionTable, RichStripeBalanceTransaction}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.JdbcProfile

import javax.inject.{Inject, Provider, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object BalanceTransactionService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    amount: Long,
    currency: String,
    description: String,
    feeAmount: Long,
    netAmount: Long,
    status: String,
    `type`: String,
    source: Option[String],
    createdAt: Instant,
    syncedAt: Instant
  )
}

@Singleton
class BalanceTransactionService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  chargeService: Provider[ChargeService],
  config: PlayConfig
)(implicit ec: ExecutionContext) extends BaseDbService {

  import BalanceTransactionService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[StripeBalanceTransactionTable] = TableQuery[StripeBalanceTransactionTable]

  def create(data: CreateData): Future[StripeBalanceTransaction] = {
    val entity = StripeBalanceTransaction(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      amount = data.amount,
      currency = data.currency,
      description = data.description,
      feeAmount = data.feeAmount,
      netAmount = data.netAmount,
      status = data.status,
      `type` = data.`type`,
      source = data.source,
      createdAt = data.createdAt,
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
              case e: PSQLException if matchUniqueConstraintException(e, "balance_transaction_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: StripeBalanceTransaction): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }


  def getById(id: String): Future[Option[StripeBalanceTransaction]] = {
    getByIds(Set(id)).map(_.headOption)
  }

  def getRichByIds(ids: Set[String]): Future[Seq[RichStripeBalanceTransaction]] = {
    getByIds(ids).flatMap(hydrate)
  }

  def getRichById(id: String): Future[Option[RichStripeBalanceTransaction]] = {
    getRichByIds(Set(id)).map(_.headOption)
  }

  def getAll(): Future[Seq[StripeBalanceTransaction]] = {
    db.run {
      query.result
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[StripeBalanceTransaction]] = {
    db.run {
      query.filter(bt => bt.id.inSet(ids)).result
    }
  }

  private def hydrate(items: Seq[StripeBalanceTransaction]): Future[Seq[RichStripeBalanceTransaction]] = {
    for {
      charges <- chargeService.get().getByIds(items.flatMap(_.source).toSet)
    } yield {
      val chargeById = charges.map(c => c.id -> c).toMap
      items.map { item =>
        RichStripeBalanceTransaction(
          base = item,
          charge = item.source.flatMap(chargeById.get)
        )
      }
    }
  }

}

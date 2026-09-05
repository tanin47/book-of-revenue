package database.services

import database.models.{Dispute, DisputeTable, RichDispute}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object DisputeService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    balanceTransactionIds: Seq[String],
    amount: Long,
    currency: String,
    chargeId: Option[String],
    paymentIntentId: Option[String],
    status: String,
    createdAt: Instant,
    syncedAt: Instant
  )
}

@Singleton
class DisputeService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  balanceTransactionService: BalanceTransactionService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import DisputeService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[DisputeTable] = TableQuery[DisputeTable]

  def create(data: CreateData): Future[Dispute] = {
    val entity = Dispute(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      balanceTransactionIds = data.balanceTransactionIds.toList,
      amount = data.amount,
      currency = data.currency,
      chargeId = data.chargeId,
      paymentIntentId = data.paymentIntentId,
      status = data.status,
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
              case e: PSQLException if matchUniqueConstraintException(e, "dispute_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: Dispute): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getById(id: String): Future[Option[Dispute]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def getAll(): Future[Seq[Dispute]] = {
    db.run {
      query.result
    }
  }

  def getByChargeIdsOrPaymentIntentIds(chargeIds: Set[String], paymentIntentIds: Set[String]): Future[Seq[Dispute]] = {
    db.run {
      query.filter(r => r.chargeId.inSet(chargeIds) || r.paymentIntentId.inSet(paymentIntentIds)).result
    }
  }

  def getByChargeIds(chargeIds: Set[String]): Future[Seq[Dispute]] = {
    db.run {
      query.filter(_.chargeId.inSet(chargeIds)).result
    }
  }

  def getRichByChargeIds(chargeIds: Set[String]): Future[Seq[RichDispute]] = {
    getByChargeIds(chargeIds).flatMap(hydrate)
  }

  private[this] def hydrate(items: Seq[Dispute]): Future[Seq[RichDispute]] = {
    for {
      balanceTransactions <- balanceTransactionService.getByIds(items.flatMap(_.balanceTransactionIds).toSet)
    } yield {
      val btById = balanceTransactions.map(bt => bt.id -> bt).toMap
      items.map { item =>
        RichDispute(
          base = item,
          balanceTransactions = item.balanceTransactionIds.flatMap(btById.get),
        )
      }
    }
  }
}

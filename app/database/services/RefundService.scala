package database.services

import database.models.{Refund, RefundTable, RichRefund}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Provider, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object RefundService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    balanceTransactionId: Option[String],
    failureBalanceTransactionId: Option[String],
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
class RefundService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  balanceTransactionService: BalanceTransactionService,
  creditNoteRefundService: Provider[CreditNoteRefundService],
)(implicit ec: ExecutionContext) extends BaseDbService {

  import RefundService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[RefundTable] = TableQuery[RefundTable]

  def create(data: CreateData): Future[Refund] = {
    val entity = Refund(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      balanceTransactionId = data.balanceTransactionId,
      failureBalanceTransactionId = data.failureBalanceTransactionId,
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
              case e: PSQLException if matchUniqueConstraintException(e, "refund_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: Refund): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getById(id: String): Future[Option[Refund]] = {
    getByIds(Set(id)).map(_.headOption)
  }

  def getAll(): Future[Seq[Refund]] = {
    db.run {
      query.result
    }
  }

  def getByChargeIdsOrPaymentIntentIds(chargeIds: Set[String], paymentIntentIds: Set[String]): Future[Seq[Refund]] = {
    db.run {
      query.filter(r => r.chargeId.inSet(chargeIds) || r.paymentIntentId.inSet(paymentIntentIds)).result
    }
  }

  def getByChargeIds(ids: Set[String]): Future[Seq[Refund]] = {
    db.run {
      query.filter(_.chargeId.inSet(ids)).result
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[Refund]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }

  def getRichByChargeIds(ids: Set[String]): Future[Seq[RichRefund]] = {
    getByChargeIds(ids).flatMap(hydrate)
  }

  def getRichByIds(ids: Set[String]): Future[Seq[RichRefund]] = {
    getByIds(ids).flatMap(hydrate)
  }

  private[this] def hydrate(items: Seq[Refund]): Future[Seq[RichRefund]] = {
    for {
      bts <- balanceTransactionService.getByIds(items.flatMap { i => Seq(i.balanceTransactionId, i.failureBalanceTransactionId).flatten }.toSet)
      creditNoteRefunds <- creditNoteRefundService.get().getByRefundIds(items.map(_.id).toSet)
    } yield {
      val btById = bts.map(bt => bt.id -> bt).toMap
      val creditNoteRefundIds = creditNoteRefunds.flatMap(_.refundId).toSet
      items.map { item =>
        RichRefund(
          base = item,
          balanceTransaction = item.balanceTransactionId.flatMap(btById.get),
          failureBalanceTransaction = item.failureBalanceTransactionId.flatMap(btById.get),
          belongsToCreditNote = creditNoteRefundIds.contains(item.id)
        )
      }
    }
  }
}

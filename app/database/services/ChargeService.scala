package database.services

import database.models.{Charge, ChargeTable, RichCharge}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object ChargeService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    balanceTransactionId: Option[String],
    customerId: Option[String],
    amount: Long,
    currency: String,
    description: Option[String],
    disputed: Boolean,
    refunded: Boolean,
    amountRefunded: Option[Long],
    paymentIntentId: Option[String],
    created: Instant,
    status: String,
    syncedAt: Instant
  )
}

@Singleton
class ChargeService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  refundService: RefundService,
  disputeService: DisputeService,
  balanceTransactionService: BalanceTransactionService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import ChargeService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[ChargeTable] = TableQuery[ChargeTable]

  def create(data: CreateData): Future[Charge] = {
    val entity = Charge(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      balanceTransactionId = data.balanceTransactionId,
      customerId = data.customerId,
      amount = data.amount,
      currency = data.currency,
      description = data.description,
      disputed = data.disputed,
      refunded = data.refunded,
      amountRefunded = data.amountRefunded,
      paymentIntentId = data.paymentIntentId,
      created = data.created,
      status = data.status,
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
              case e: PSQLException if matchUniqueConstraintException(e, "charge_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: Charge): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getAll(): Future[Seq[Charge]] = {
    db.run {
      query.result
    }
  }

  def getByIdsOrPaymentIntentIds(ids: Set[String], paymentIntentIds: Set[String]): Future[Seq[Charge]] = {
    db.run {
      query.filter { q => q.id.inSet(ids) || q.paymentIntentId.inSet(paymentIntentIds) }.result
    }
  }

  def getAllStandaloneChargeSources(): Future[Seq[database.models.RevRecTransaction.Source]] = {
    db.run {
      sql"""
        SELECT
          charge.id, charge.stripe_account_id, charge.live_mode, charge.customer_id
        FROM charge
        LEFT JOIN invoice_payment
        ON charge.id = invoice_payment.charge_id
        WHERE invoice_payment.charge_id IS NULL AND charge.payment_intent_id IS NULL;
      """.as[(String, String, Boolean, Option[String])]
    }.map(_.map { case (id, accountId, liveMode, customerId) => database.models.RevRecTransaction.Source(id, accountId, liveMode, customerId) })
  }

  def getByIds(ids: Set[String]): Future[Seq[Charge]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }

  def getById(id: String): Future[Option[Charge]] = {
    getByIds(Set(id)).map(_.headOption)
  }

  def getRichByIds(ids: Set[String]): Future[Seq[RichCharge]] = {
    getByIds(ids).flatMap(hydrate)
  }

  def getRichById(id: String): Future[Option[RichCharge]] = {
    getRichByIds(Set(id)).map(_.headOption)
  }

  private[this] def hydrate(items: Seq[Charge]): Future[Seq[RichCharge]] = {
    for {
      balanceTransactions <- balanceTransactionService.getByIds(items.flatMap(_.balanceTransactionId).toSet)
      disputes <- disputeService.getRichByChargeIds(items.map(_.id).toSet)
      refunds <- refundService.getRichByChargeIds(items.map(_.id).toSet)
    } yield {
      val btById = balanceTransactions.map(bt => bt.id -> bt).toMap
      val disputeByChargeId = disputes.groupBy(_.base.chargeId.get)
      val refundByChargeId = refunds.groupBy(_.base.chargeId.get)

      items.map { item =>
        RichCharge(
          base = item,
          balanceTransaction = item.balanceTransactionId.flatMap(btById.get),
          disputes = disputeByChargeId.getOrElse(item.id, Seq.empty).sortBy(_.base.createdAt),
          refunds = refundByChargeId.getOrElse(item.id, Seq.empty).sortBy(_.base.createdAt),
        )
      }
    }
  }

}

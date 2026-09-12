package database.services

import database.models.stripe.{StripePaymentIntent, StripePaymentIntentTable, RichStripePaymentIntent}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object PaymentIntentService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    customerId: Option[String],
    amount: Long,
    currency: String,
    description: Option[String],
    latestCharge: Option[String],
    syncedAt: Instant
  )
}

@Singleton
class PaymentIntentService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  chargeService: ChargeService
)(implicit ec: ExecutionContext) extends BaseDbService {

  import PaymentIntentService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[StripePaymentIntentTable] = TableQuery[StripePaymentIntentTable]

  def create(data: CreateData): Future[StripePaymentIntent] = {
    val entity = StripePaymentIntent(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      customerId = data.customerId,
      amount = data.amount,
      currency = data.currency,
      description = data.description,
      latestChargeId = data.latestCharge,
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
              case e: PSQLException if matchUniqueConstraintException(e, "payment_intent_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: StripePaymentIntent): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getAll(): Future[Seq[StripePaymentIntent]] = {
    db.run {
      query.result
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[StripePaymentIntent]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }

  def getAllStandalonePaymentIntentSources(): Future[Seq[database.models.Transaction.Source]] = {
    db.run {
      sql"""
        SELECT
          p.id, p.stripe_account_id, p.live_mode, p.customer_id
        FROM stripe.payment_intent p
        LEFT JOIN stripe.invoice_payment
        ON p.id = invoice_payment.payment_intent_id
        WHERE invoice_payment.payment_intent_id IS NULL;
      """.as[(String, String, Boolean, Option[String])]
    }.map(_.map { case (id, accountId, liveMode, customerId) => database.models.Transaction.Source(id, accountId, liveMode, customerId) })
  }

  def getById(id: String): Future[Option[StripePaymentIntent]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def getRichById(id: String): Future[Option[RichStripePaymentIntent]] = {
    getRichByIds(Set(id)).map(_.headOption)
  }

  def getRichByIds(ids: Set[String]): Future[Seq[RichStripePaymentIntent]] = {
    getByIds(ids).flatMap { items => hydrate(items.toList) }
  }

  private[this] def hydrate(items: List[StripePaymentIntent]): Future[List[RichStripePaymentIntent]] = {
    for {
      charges <- chargeService.getRichByIds(items.flatMap(_.latestChargeId).toSet)
    } yield {
      val chargeById = charges.groupBy(_.base.id).view.mapValues(_.head).toMap
      items.map { item =>
        RichStripePaymentIntent(
          base = item,
          charge = item.latestChargeId.flatMap { chargeId => chargeById.get(chargeId) }
        )
      }
    }
  }

}

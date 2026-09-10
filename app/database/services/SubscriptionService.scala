package database.services

import database.models.stripe.{RichStripeSubscription, StripeSubscription, StripeSubscriptionTable}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object SubscriptionService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    customerId: String,
    currency: String,
    status: String,
    startDate: Instant,
    discountIds: List[String],
    defaultTaxRateIds: List[String],
    syncedAt: Instant,
  )
}

@Singleton
class SubscriptionService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  discountService: DiscountService,
  taxRateService: TaxRateService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import SubscriptionService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[StripeSubscriptionTable] = TableQuery[StripeSubscriptionTable]

  def create(data: CreateData): Future[StripeSubscription] = {
    val entity = StripeSubscription(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      customerId = data.customerId,
      currency = data.currency,
      status = data.status,
      startDate = data.startDate,
      discountIds = data.discountIds,
      defaultTaxRateIds = data.defaultTaxRateIds,
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
              case e: PSQLException if matchUniqueConstraintException(e, "subscription_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: StripeSubscription): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getAll(stripeAccountId: String, liveMode: Boolean): Future[Seq[StripeSubscription]] = {
    db.run {
      query.filter { q => q.stripeAccountId === stripeAccountId && q.liveMode === liveMode }.result
    }
  }

  def getById(id: String): Future[Option[StripeSubscription]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[StripeSubscription]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }

  def getRichById(id: String): Future[Option[RichStripeSubscription]] = {
    getRichByIds(Set(id)).map(_.headOption)
  }

  def getRichByIds(ids: Set[String]): Future[Seq[RichStripeSubscription]] = {
    getByIds(ids).flatMap(hydrate)
  }

  private[this] def hydrate(items: Seq[StripeSubscription]): Future[Seq[RichStripeSubscription]] = {
    for {
      discounts <- discountService.getRichByIds(items.flatMap(_.discountIds).toSet)
      taxRates <- taxRateService.getByIds(items.flatMap(_.defaultTaxRateIds).toSet)
      discountsById = discounts.map { d => d.base.id -> d }.toMap
      taxRatesById = taxRates.map { t => t.id -> t }.toMap
    } yield {
      items.map { item =>
        RichStripeSubscription(
          base = item,
          discounts = item.discountIds.flatMap(discountsById.get),
          defaultTaxRates = item.defaultTaxRateIds.flatMap(taxRatesById.get)
        )
      }
    }
  }
}

package database.services

import database.models.*
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider
import services.ExchangeRate

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object SubscriptionItemService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    subscriptionId: String,
    priceId: String,
    quantity: Long,
    currentPeriodEnd: Instant,
    currentPeriodStart: Instant,
    discountIds: List[String],
    taxRateIds: List[String],
    syncedAt: Instant,
  )
}

@Singleton
class SubscriptionItemService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  priceService: PriceService,
  meterEventSummaryService: MeterEventSummaryService,
  subscriptionService: SubscriptionService,
  discountService: DiscountService,
  taxRateService: TaxRateService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import SubscriptionItemService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[SubscriptionItemTable] = TableQuery[SubscriptionItemTable]

  def create(data: CreateData): Future[SubscriptionItem] = {
    val entity = SubscriptionItem(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      subscriptionId = data.subscriptionId,
      priceId = data.priceId,
      quantity = data.quantity,
      currentPeriodEnd = data.currentPeriodEnd,
      currentPeriodStart = data.currentPeriodStart,
      discountIds = data.discountIds,
      taxRateIds = data.taxRateIds,
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
              case e: PSQLException if matchUniqueConstraintException(e, "subscription_item_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: SubscriptionItem): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getById(id: String): Future[Option[SubscriptionItem]] = {
    getByIds(Set(id)).map(_.headOption)
  }

  def getAll(stripeAccountId: String, liveMode: Boolean): Future[Seq[SubscriptionItem]] = {
    db.run {
      query.filter { q => q.stripeAccountId === stripeAccountId && q.liveMode === liveMode }.result
    }
  }

  def getBySubscriptionIds(subscriptionIds: Set[String]): Future[Seq[SubscriptionItem]] = {
    db.run {
      query.filter(_.subscriptionId.inSet(subscriptionIds)).result
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[SubscriptionItem]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }

  def getAllUnbilledUsageSubscriptionSources(): Future[Seq[database.models.RevRecTransaction.Source]] = {
    db.run {
      sql"""
        SELECT
          si.id, si.stripe_account_id, si.live_mode, s.customer_id
        FROM subscription_item si
        LEFT JOIN price p ON si.price_id = p.id
        LEFT JOIN subscription s ON si.subscription_id = s.id
        LEFT JOIN invoice_line_item il ON il.subscription_item_id = si.id
        WHERE
          p.recurring_meter_id IS NOT NULL
          AND p.recurring_usage_type = 'metered'
          AND (
            il.id IS NULL
            OR il.started_at < si.current_period_start
          )
      """.as[(String, String, Boolean, Option[String])]
    }.map(_.map { case (id, accountId, liveMode, customerId) => database.models.RevRecTransaction.Source(id, accountId, liveMode, customerId) })
  }

  def getRichById(id: String): Future[Option[RichSubscriptionItem]] = {
    db.run(query.filter(_.id === id).result.headOption).flatMap {
      case None => Future.successful(None)
      case Some(item) => hydrate(item)
    }
  }

  private[this] def hydrate(item: SubscriptionItem): Future[Option[RichSubscriptionItem]] = {
    for {
      subscriptionOpt <- subscriptionService.getRichById(item.subscriptionId)
      price <- priceService.getRichById(item.priceId)
      discounts <- discountService.getRichByIds(item.discountIds.toSet)
      taxRates <- taxRateService.getByIds(item.taxRateIds.toSet)
      meterEventSummaries <- (price.flatMap(_.base.recurringMeterId), subscriptionOpt) match {
        case (Some(meterId), Some(subscription)) => meterEventSummaryService.getByMeterIdAndCustomerId(meterId, subscription.base.customerId)
        case _ => Future.successful(Seq.empty[MeterEventSummary])
      }
    } yield {
      val discountsById = discounts.map { d => d.base.id -> d }.toMap
      val taxRatesById = taxRates.map { t => t.id -> t }.toMap

      subscriptionOpt.map { subscription =>
        RichSubscriptionItem(
          base = item,
          subscription = subscription,
          price = price,
          meterEventSummaries = meterEventSummaries.filter { e =>
            (e.startTime.toEpochMilli <= item.currentPeriodStart.toEpochMilli && item.currentPeriodStart.isBefore(e.endTime)) ||
              (e.startTime.toEpochMilli <= item.currentPeriodEnd.toEpochMilli && item.currentPeriodEnd.isBefore(e.endTime)) ||
              (item.currentPeriodStart.toEpochMilli <= e.startTime.toEpochMilli && e.endTime.isBefore(item.currentPeriodEnd))
          },
          discounts = item.discountIds.flatMap(discountsById.get),
          taxRates = item.taxRateIds.flatMap(taxRatesById.get),
          // Usage is booked in the subscription's currency, so settlement and presentment are the same currency.
          currentPeriodStartExchangeRate = ExchangeRate.sameCurrency(subscription.base.currency)
        )
      }
    }
  }
}

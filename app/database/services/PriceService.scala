package database.services

import database.models.stripe.{StripePrice, StripePriceTable, RichStripePrice}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object PriceService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    currency: String,
    productId: String,
    `type`: String,
    billingScheme: String,
    unitAmount: Long,
    tiersMode: Option[String],
    recurringInterval: Option[String],
    recurringIntervalCount: Option[Int],
    recurringMeterId: Option[String],
    recurringUsageType: Option[String],
    syncedAt: Instant,
  )
}

@Singleton
class PriceService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  priceTierService: PriceTierService,
  productService: ProductService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import PriceService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[StripePriceTable] = TableQuery[StripePriceTable]

  def create(data: CreateData): Future[StripePrice] = {
    val entity = StripePrice(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      currency = data.currency,
      productId = data.productId,
      `type` = data.`type`,
      billingScheme = data.billingScheme,
      unitAmount = data.unitAmount,
      tiersMode = data.tiersMode,
      recurringInterval = data.recurringInterval,
      recurringIntervalCount = data.recurringIntervalCount,
      recurringMeterId = data.recurringMeterId,
      recurringUsageType = data.recurringUsageType,
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
              case e: PSQLException if matchUniqueConstraintException(e, "price_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: StripePrice): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getAll(stripeAccountId: String, liveMode: Boolean): Future[Seq[StripePrice]] = {
    db.run {
      query.filter { q => q.stripeAccountId === stripeAccountId && q.liveMode === liveMode }.result
    }
  }

  def getById(id: String): Future[Option[StripePrice]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[StripePrice]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }

  def getRichById(id: String): Future[Option[RichStripePrice]] = {
    getRichByIds(Set(id)).map(_.headOption)
  }

  def getRichByIds(ids: Set[String]): Future[Seq[RichStripePrice]] = {
    getByIds(ids).flatMap { items => hydrate(items.toList) }
  }

  private[this] def hydrate(items: List[StripePrice]): Future[Seq[RichStripePrice]] = {
    for {
      tiers <- priceTierService.getByPriceIds(items.map(_.id).toSet)
      products <- productService.getByIds(items.map(_.productId).toSet)
    } yield {
      val tiersByPrice = tiers.groupBy(_.priceId)
      val productsById = products.map { p => p.id -> p }.toMap

      items.map { item =>
        RichStripePrice(
          base = item,
          product = productsById.get(item.productId),
          tiers = tiersByPrice.getOrElse(item.id, Seq.empty)
        )
      }
    }
  }
}

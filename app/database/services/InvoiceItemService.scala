package database.services

import database.models.{InvoiceItem, InvoiceItemTable, RichInvoiceItem}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider
import services.ExchangeRateService

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object InvoiceItemService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    invoiceId: Option[String],
    customerId: String,
    amount: Long,
    currency: String,
    description: Option[String],
    startedAt: Option[Instant],
    endedAt: Option[Instant],
    discountIds: List[String],
    taxRateIds: List[String],
    priceId: Option[String],
    productId: Option[String],
    createdAt: Instant,
    syncedAt: Instant
  )
}

@Singleton
class InvoiceItemService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  exchangeRateService: ExchangeRateService,
  discountService: DiscountService,
  taxRateService: TaxRateService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import InvoiceItemService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[InvoiceItemTable] = TableQuery[InvoiceItemTable]

  def create(item: CreateData): Future[InvoiceItem] = {
    val entity = InvoiceItem(
      stripeAccountId = item.stripeAccountId,
      liveMode = item.liveMode,
      id = item.id,
      invoiceId = item.invoiceId,
      customerId = item.customerId,
      amount = item.amount,
      currency = item.currency,
      description = item.description,
      startedAt = item.startedAt,
      endedAt = item.endedAt,
      discountIds = item.discountIds,
      taxRateIds = item.taxRateIds,
      priceId = item.priceId,
      productId = item.productId,
      createdAt = item.createdAt,
      syncedAt = item.syncedAt
    )

    for {
      existing <- getById(entity.id)
      _ <- existing match {
        case Some(_) => update(entity)
        case None =>
          db
            .run { query += entity }
            .recoverWith {
              case e: PSQLException if matchUniqueConstraintException(e, "invoice_item_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: InvoiceItem): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getAll(): Future[Seq[InvoiceItem]] = {
    db.run {
      query.result
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[InvoiceItem]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }

  def getById(id: String): Future[Option[InvoiceItem]] = {
    getByIds(Set(id)).map(_.headOption)
  }

  def getRichById(id: String): Future[Option[RichInvoiceItem]] = {
    getRichByIds(Set(id)).map(_.headOption)
  }

  def getRichByIds(ids: Set[String]): Future[Seq[RichInvoiceItem]] = {
    getByIds(ids).flatMap(hydrate)
  }

  private[this] def hydrate(items: Seq[InvoiceItem]): Future[Seq[RichInvoiceItem]] = {
    for {
      discounts <- discountService.getRichByIds(items.flatMap(_.discountIds).toSet)
      taxRates <- taxRateService.getByIds(items.flatMap(_.taxRateIds).toSet)
    } yield {
      val discountsById = discounts.map { d => d.base.id -> d }.toMap
      val taxRatesById = taxRates.map { t => t.id -> t }.toMap
      items.map { item =>
       RichInvoiceItem(
         base = item,
         discounts = item.discountIds.flatMap(discountsById.get),
         taxRates = item.taxRateIds.flatMap(taxRatesById.get),
       )
     }
    }
  }

  def getByInvoice(invoiceId: String): Future[Seq[InvoiceItem]] = {
    getByInvoiceIds(Set(invoiceId))
  }

  def getByInvoiceIds(invoiceIds: Set[String]): Future[Seq[InvoiceItem]] = {
    db.run {
      query.filter(_.invoiceId.inSet(invoiceIds)).result
    }
  }

  def getAllUnbilledInvoiceItemSources(): Future[Seq[database.models.RevRecTransaction.Source]] = {
    db.run {
      sql"""
            SELECT
              invoice_item.id, invoice_item.stripe_account_id, invoice_item.live_mode, invoice_item.customer_id
            FROM invoice_item
            LEFT JOIN invoice_line_item ON invoice_item.id = invoice_line_item.invoice_item_id
            LEFT JOIN invoice ON invoice_line_item.invoice_id = invoice.id
            WHERE invoice_line_item.id IS NULL OR invoice.id IS NULL OR invoice.finalized_at IS NULL;
          """.as[(String, String, Boolean, Option[String])]
    }.map(_.map { case (id, accountId, liveMode, customerId) => database.models.RevRecTransaction.Source(id, accountId, liveMode, customerId) })
  }
}

package database.services

import database.models.{InvoiceLineItem, InvoiceLineItemTable, RichInvoiceLineItem}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider
import services.ExchangeRateService

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object InvoiceLineItemService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    invoiceId: String,
    description: Option[String],
    amount: Long,
    currency: String,
    startedAt: Option[Instant],
    endedAt: Option[Instant],
    rank: Int,
    invoiceItemId: Option[String],
    subscriptionItemId: Option[String],
    priceId: Option[String],
    pricingUnitAmountDecimal: Option[String],
    customerId: String,
    syncedAt: Instant
  )
}

@Singleton
class InvoiceLineItemService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  invoiceItemService: InvoiceItemService,
  subscriptionItemService: SubscriptionItemService,
  priceService: PriceService,
  exchangeRateService: ExchangeRateService,
  invoiceLineItemTaxService: InvoiceLineItemTaxService,
  invoiceLineItemPretaxCreditAmountService: InvoiceLineItemPretaxCreditAmountService,
  creditBalanceTransactionService: CreditBalanceTransactionService,
  meterEventSummaryService: MeterEventSummaryService
)(implicit ec: ExecutionContext) extends BaseDbService {

  import InvoiceLineItemService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[InvoiceLineItemTable] = TableQuery[InvoiceLineItemTable]

  def create(item: CreateData): Future[InvoiceLineItem] = {
    val entity = InvoiceLineItem(
      stripeAccountId = item.stripeAccountId,
      liveMode = item.liveMode,
      id = item.id,
      invoiceId = item.invoiceId,
      description = item.description,
      amount = item.amount,
      currency = item.currency,
      startedAt = item.startedAt,
      endedAt = item.endedAt,
      rank = item.rank,
      invoiceItemId = item.invoiceItemId,
      subscriptionItemId = item.subscriptionItemId,
      priceId = item.priceId,
      pricingUnitAmountDecimal = item.pricingUnitAmountDecimal,
      customerId = item.customerId,
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
              case e: PSQLException if matchUniqueConstraintException(e, "invoice_line_item_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: InvoiceLineItem): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getById(id: String): Future[Option[InvoiceLineItem]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def getAll(): Future[Seq[InvoiceLineItem]] = {
    db.run {
      query.result
    }
  }

  def getByInvoice(invoiceId: String): Future[Seq[InvoiceLineItem]] = {
    getByInvoiceIds(Set(invoiceId))
  }

  def getByInvoiceIds(invoiceIds: Set[String]): Future[Seq[InvoiceLineItem]] = {
    db.run {
      query.filter(_.invoiceId.inSet(invoiceIds)).sortBy(_.rank.asc).result
    }
  }

  def getRichByInvoiceIds(invoiceIds: Set[String]): Future[Seq[RichInvoiceLineItem]] = {
    getByInvoiceIds(invoiceIds).flatMap(hydrate)
  }

  private[this] def hydrate(items: Seq[InvoiceLineItem]): Future[Seq[RichInvoiceLineItem]] = {
    val futureBareRichItems = for {
      invoiceItems <- invoiceItemService.getRichByIds(items.flatMap(_.invoiceItemId).toSet)
      subscriptionItems <- subscriptionItemService.getByIds(items.flatMap(_.subscriptionItemId).toSet)
      prices <- priceService.getRichByIds(items.flatMap(_.priceId).toSet)
      taxes <- invoiceLineItemTaxService.getByInvoiceLineItemIds(items.map(_.id).toSet)
      pretaxCreditAmounts <- invoiceLineItemPretaxCreditAmountService.getRichByInvoiceLineItemIds(items.map(_.id).toSet)
      creditBalanceTransactionsAppliedOnVoid <- creditBalanceTransactionService.getRichByCreditInvoiceVoidedInvoiceLineItemIds(items.map(_.id).toSet)
      invoiceItemsMap = invoiceItems.map { i => i.base.id -> i }.toMap
      subscriptionItemsMap = subscriptionItems.map { s => s.id -> s }.toMap
      pricesMap = prices.map { p => p.base.id -> p }.toMap
      taxesByLineItem = taxes.groupBy(_.invoiceLineItemId)
      pretaxCreditAmountsByLineItem = pretaxCreditAmounts.groupBy(_.base.invoiceLineItemId)
      creditBalanceTransactionsAppliedOnVoidByLineItem = creditBalanceTransactionsAppliedOnVoid.groupBy(_.base.creditInvoiceVoidedInvoiceLineItemId.get)
      result <- Future.sequence(items.map { item =>
        val settlementCurrency = item.currency // TODO: replace with the account config. Exchange rate should be chosen at a later time to be honest
        val timestamp = item.startedAt.getOrElse(item.syncedAt)
        exchangeRateService.get(None, item.currency, settlementCurrency, timestamp).map { exchangeRate =>
          RichInvoiceLineItem(
            base = item,
            invoiceItem = item.invoiceItemId.flatMap(invoiceItemsMap.get),
            subscriptionItem = item.subscriptionItemId.flatMap(subscriptionItemsMap.get),
            price = item.priceId.flatMap(pricesMap.get),
            meterEventSummaries = Seq.empty,
            startedAtExchangeRate = exchangeRate,
            pretaxCreditAmounts = pretaxCreditAmountsByLineItem.getOrElse(item.id, Seq.empty).sortBy(_.base.rank),
            taxes = taxesByLineItem.getOrElse(item.id, Seq.empty).sortBy(_.rank),
            creditBalanceTransactionsAppliedOnVoid = creditBalanceTransactionsAppliedOnVoidByLineItem.getOrElse(item.id, Seq.empty)
          )
        }
      })
    } yield {
      result
    }

    futureBareRichItems.flatMap { bareRichItems =>
      Future.sequence(
        bareRichItems.map { item =>
          item.price.flatMap(_.base.recurringMeterId) match {
            case Some(meterId) =>
              meterEventSummaryService.getByMeterIdAndCustomerId(meterId, item.base.customerId).map { meterEventSummaries =>
                item.copy(
                  meterEventSummaries = meterEventSummaries.filter { e =>
                    val contain = for {
                      startedAt <- item.base.startedAt
                      endedAt <- item.base.endedAt
                    } yield {
                      startedAt.toEpochMilli <= e.startTime.toEpochMilli && e.endTime.isBefore(endedAt)
                    }
                    item.base.startedAt.exists { s => e.startTime.toEpochMilli <= s.toEpochMilli && s.isBefore(e.endTime) } ||
                      item.base.endedAt.exists { s => e.startTime.toEpochMilli <= s.toEpochMilli && s.isBefore(e.endTime) } ||
                      contain.getOrElse(false)
                  }
                )
              }
            case None => Future(item)
          }
        }
      )
    }
  }
}

package database.services

import database.models.{Invoice, InvoiceTable, RichInvoice}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider
import services.ExchangeRateService

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object InvoiceService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    customerId: String,
    number: Option[String],
    total: Long,
    amountPaid: Long,
    amountOverpaid: Long,
    amountRemaining: Long,
    currency: String,
    finalizedAt: Option[Instant],
    paidAt: Option[Instant],
    dueAt: Option[Instant],
    markedUncollectibleAt: Option[Instant],
    voidedAt: Option[Instant],
    startingBalance: Option[Long],
    endingBalance: Option[Long],
    status: String,
    syncedAt: Instant
  )
}

@Singleton
class InvoiceService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  invoiceLineItemService: InvoiceLineItemService,
  invoicePaymentService: InvoicePaymentService,
  customerBalanceTransactionService: CustomerBalanceTransactionService,
  creditNoteService: CreditNoteService,
  exchangeRateService: ExchangeRateService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import InvoiceService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[InvoiceTable] = TableQuery[InvoiceTable]

  def create(data: CreateData): Future[Invoice] = {
    val entity = Invoice(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      customerId = data.customerId,
      number = data.number,
      total = data.total,
      amountPaid = data.amountPaid,
      amountOverpaid = data.amountOverpaid,
      amountRemaining = data.amountRemaining,
      currency = data.currency,
      finalizedAt = data.finalizedAt,
      paidAt = data.paidAt,
      dueAt = data.dueAt,
      markedUncollectibleAt = data.markedUncollectibleAt,
      voidedAt = data.voidedAt,
      startingBalance = data.startingBalance,
      endingBalance = data.endingBalance,
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
              case e: PSQLException if matchUniqueConstraintException(e, "invoice_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: Invoice): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getAll(): Future[Seq[Invoice]] = {
    db.run {
      query.result
    }
  }

  def getAllInvoiceSources(): Future[Seq[database.models.RevRecTransaction.Source]] = {
    db.run {
      query.map(q => (q.id, q.stripeAccountId, q.liveMode, q.customerId)).result
    }.map(_.map { case (id, accountId, liveMode, customerId) => database.models.RevRecTransaction.Source(id, accountId, liveMode, Some(customerId)) })
  }

  def getById(id: String): Future[Option[Invoice]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[Invoice]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }

  def getRichById(id: String): Future[Option[RichInvoice]] = {
    getById(id).flatMap { item => hydrate(item.toList) }.map(_.headOption)
  }

  def getRichByIds(ids: Set[String]): Future[Seq[RichInvoice]] = {
    getByIds(ids).flatMap { items => hydrate(items.toList) }
  }

  private[this] def hydrate(items: List[Invoice]): Future[Seq[RichInvoice]] = {
    val invoiceIds = items.map(_.id).toSet

    for {
      lineItems <- invoiceLineItemService.getRichByInvoiceIds(invoiceIds)
      payments <- invoicePaymentService.getRichByInvoiceIds(invoiceIds)
      customerBalanceTransactions <- customerBalanceTransactionService.getByInvoiceIds(invoiceIds)
      creditNotes <- creditNoteService.getRichByInvoiceIds(invoiceIds)
      lineItemsByInvoice = lineItems.groupBy(_.base.invoiceId)
      paymentsByInvoice = payments.groupBy(_.base.invoiceId)
      customerBalanceTransactionsByInvoice = customerBalanceTransactions.groupBy(_.invoiceId.get)
      creditNotesByInvoice = creditNotes.groupBy(_.base.invoiceId)
    } yield {
      items.map { item =>
        RichInvoice(
          base = item,
          lineItems = lineItemsByInvoice.getOrElse(item.id, Seq.empty).sortBy(_.base.rank),
          payments = paymentsByInvoice.getOrElse(item.id, Seq.empty).sortBy(_.base.createdAt),
          customerBalanceTransactions = customerBalanceTransactionsByInvoice.getOrElse(item.id, Seq.empty).sortBy(_.createdAt),
          creditNotes = creditNotesByInvoice.getOrElse(item.id, Seq.empty).sortBy(_.base.createdAt),
        )
      }
    }
  }
}

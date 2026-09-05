package database.services

import database.models.{CreditNote, CreditNoteTable, RichCreditNote, RichCreditNoteRefund}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object CreditNoteService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    `type`: String,
    invoiceId: String,
    currency: String,
    total: Long,
    prePaymentAmount: Long,
    customerBalanceTransactionId: Option[String],
    outOfBandAmount: Option[Long],
    createdAt: Instant,
    effectiveAt: Option[Instant],
    voidedAt: Option[Instant],
  )
}

@Singleton
class CreditNoteService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  creditNoteLineItemService: CreditNoteLineItemService,
  creditNoteRefundService: CreditNoteRefundService,
  customerBalanceTransactionService: CustomerBalanceTransactionService,
  refundService: RefundService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import CreditNoteService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[CreditNoteTable] = TableQuery[CreditNoteTable]

  def create(data: CreateData): Future[CreditNote] = {
    val entity = CreditNote(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      `type` = data.`type`,
      invoiceId = data.invoiceId,
      currency = data.currency,
      total = data.total,
      prePaymentAmount = data.prePaymentAmount,
      customerBalanceTransactionId = data.customerBalanceTransactionId,
      outOfBandAmount = data.outOfBandAmount,
      createdAt = data.createdAt,
      effectiveAt = data.effectiveAt,
      voidedAt = data.voidedAt
    )

    for {
      existing <- getById(entity.id)
      _ <- existing match {
        case Some(_) => update(entity)
        case None =>
          db
            .run { query += entity }
            .recoverWith {
              case e: PSQLException if matchUniqueConstraintException(e, "credit_note_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: CreditNote): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getById(id: String): Future[Option[CreditNote]] = {
    getByIds(Set(id)).map(_.headOption)
  }

  def getAll(): Future[Seq[CreditNote]] = {
    db.run {
      query.result
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[CreditNote]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }

  def getByInvoiceIds(invoiceIds: Set[String]): Future[Seq[CreditNote]] = {
    db.run {
      query.filter(_.invoiceId.inSet(invoiceIds)).result
    }
  }

  def getRichByInvoiceIds(invoiceIds: Set[String]): Future[Seq[RichCreditNote]] = {
    getByInvoiceIds(invoiceIds).flatMap(hydrate)
  }

  private[this] def hydrate(items: Seq[CreditNote]): Future[Seq[RichCreditNote]] = {
    val creditNoteIds = items.map(_.id).toSet
    val customerBalanceTransactionIds = items.flatMap(_.customerBalanceTransactionId).toSet

    for {
      lines <- creditNoteLineItemService.getRichByCreditNoteIds(creditNoteIds)
      refunds <- creditNoteRefundService.getByCreditNoteIds(creditNoteIds)
      richRefunds <- refundService.getRichByIds(refunds.flatMap(_.refundId).toSet)
      customerBalanceTransactions <- customerBalanceTransactionService.getByIds(customerBalanceTransactionIds)
    } yield {
      val linesByCreditNote = lines.groupBy(_.base.creditNoteId)
      val refundsByCreditNote = refunds.groupBy(_.creditNoteId)
      val richRefundById = richRefunds.map(r => r.base.id -> r).toMap
      val customerBalanceTransactionById = customerBalanceTransactions.map(cbt => cbt.id -> cbt).toMap

      items.map { item =>
        RichCreditNote(
          base = item,
          customerBalanceTransaction = item.customerBalanceTransactionId.flatMap(customerBalanceTransactionById.get),
          lines = linesByCreditNote.getOrElse(item.id, Seq.empty).sortBy(_.base.rank),
          refunds = refundsByCreditNote.getOrElse(item.id, Seq.empty).sortBy(_.rank).map { refund =>
            RichCreditNoteRefund(
              base = refund,
              refund = refund.refundId.flatMap(richRefundById.get),
            )
          },
        )
      }
    }
  }
}

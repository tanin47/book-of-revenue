package database.services

import database.models.Transaction
import database.models.stripe.{StripeCreditBalanceTransaction, StripeCreditBalanceTransactionTable, RichStripeCreditBalanceTransaction}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.JdbcProfile

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object CreditBalanceTransactionService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    createdAt: Instant,
    effectiveAt: Instant,
    `type`: Option[String],
    creditGrantId: String,
    creditAmount: Option[Long],
    creditCurrency: Option[String],
    creditType: Option[String],
    creditInvoiceVoidedInvoiceId: Option[String],
    creditInvoiceVoidedInvoiceLineItemId: Option[String],
    debitAmount: Option[Long],
    debitCurrency: Option[String],
    debitType: Option[String],
    debitCreditsAppliedInvoiceId: Option[String],
    debitCreditsAppliedInvoiceLineItemId: Option[String],
    syncedAt: Instant,
  )
}

@Singleton
class CreditBalanceTransactionService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  creditGrantService: CreditGrantService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import CreditBalanceTransactionService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[StripeCreditBalanceTransactionTable] = TableQuery[StripeCreditBalanceTransactionTable]

  def create(data: CreateData): Future[StripeCreditBalanceTransaction] = {
    val entity = StripeCreditBalanceTransaction(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      createdAt = data.createdAt,
      effectiveAt = data.effectiveAt,
      `type` = data.`type`,
      creditGrantId = data.creditGrantId,
      creditAmount = data.creditAmount,
      creditCurrency = data.creditCurrency,
      creditType = data.creditType,
      creditInvoiceVoidedInvoiceId = data.creditInvoiceVoidedInvoiceId,
      creditInvoiceVoidedInvoiceLineItemId = data.creditInvoiceVoidedInvoiceLineItemId,
      debitAmount = data.debitAmount,
      debitCurrency = data.debitCurrency,
      debitType = data.debitType,
      debitCreditsAppliedInvoiceId = data.debitCreditsAppliedInvoiceId,
      debitCreditsAppliedInvoiceLineItemId = data.debitCreditsAppliedInvoiceLineItemId,
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
              case e: PSQLException if matchUniqueConstraintException(e, "credit_balance_transaction_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: StripeCreditBalanceTransaction): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getById(id: String): Future[Option[StripeCreditBalanceTransaction]] = {
    getByIds(Set(id)).map(_.headOption)
  }

  def getAll(): Future[Seq[StripeCreditBalanceTransaction]] = {
    db.run {
      query.result
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[StripeCreditBalanceTransaction]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }

  def getRichById(transactionId: String): Future[Option[RichStripeCreditBalanceTransaction]] = {
    getRichByIds(Set(transactionId)).map(_.headOption)
  }

  def getRichByIds(ids: Set[String]): Future[Seq[RichStripeCreditBalanceTransaction]] = {
    getByIds(ids).flatMap(hydrate)
  }

  def getRichByCreditInvoiceVoidedInvoiceLineItemIds(invoiceLineItemIds: Set[String]): Future[Seq[RichStripeCreditBalanceTransaction]] = {
    db.run {
      query.filter(_.creditInvoiceVoidedInvoiceLineItemId.inSet(invoiceLineItemIds)).result
    }.flatMap(hydrate)
  }

  private[this] def hydrate(items: Seq[StripeCreditBalanceTransaction]): Future[Seq[RichStripeCreditBalanceTransaction]] = {
    creditGrantService.getByIds(items.map(_.creditGrantId).toSet).map { creditGrants =>
      val creditGrantsById = creditGrants.map { g => g.id -> g }.toMap

      items.map { item =>
        RichStripeCreditBalanceTransaction(
          base = item,
          creditGrant = creditGrantsById.get(item.creditGrantId)
        )
      }
    }
  }

  def getAllCreditBalanceTransactionSources(): Future[Seq[Transaction.Source]] = {
    db.run {
      sql"""
        SELECT txn.id, txn.stripe_account_id, txn.live_mode, credit_grant.customer_id
        FROM stripe.credit_balance_transaction txn
        LEFT JOIN stripe.credit_grant ON credit_grant.id = txn.credit_grant_id
        WHERE txn.credit_invoice_voided_invoice_id IS NULL AND txn.debit_credits_applied_invoice_id IS NULL;
      """.as[(String, String, Boolean, Option[String])]
    }.map(_.map { case (id, accountId, liveMode, customerId) => database.models.Transaction.Source(id, accountId, liveMode, customerId) })
  }

}

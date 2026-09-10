package database.services

import database.models.Transaction
import database.models.stripe.{StripeCustomerBalanceTransaction, StripeCustomerBalanceTransactionTable}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object CustomerBalanceTransactionService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    amount: Long,
    created: Instant,
    currency: String,
    customerId: String,
    description: Option[String],
    endingBalance: Long,
    invoiceId: Option[String],
    creditNoteId: Option[String],
    `type`: String,
    syncedAt: Instant
  )
}

@Singleton
class CustomerBalanceTransactionService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import CustomerBalanceTransactionService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[StripeCustomerBalanceTransactionTable] = TableQuery[StripeCustomerBalanceTransactionTable]

  def create(data: CreateData): Future[StripeCustomerBalanceTransaction] = {
    val entity = StripeCustomerBalanceTransaction(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      amount = data.amount,
      createdAt = data.created,
      currency = data.currency,
      customerId = data.customerId,
      description = data.description,
      endingBalance = data.endingBalance,
      invoiceId = data.invoiceId,
      creditNoteId = data.creditNoteId,
      `type` = data.`type`,
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
              case e: PSQLException if matchUniqueConstraintException(e, "customer_balance_transaction_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: StripeCustomerBalanceTransaction): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getById(id: String): Future[Option[StripeCustomerBalanceTransaction]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def getByCustomerId(customerId: String): Future[Seq[StripeCustomerBalanceTransaction]] = {
    db.run {
      query.filter(_.customerId === customerId).result
    }
  }

  def getByInvoiceIds(invoiceIds: Set[String]): Future[Seq[StripeCustomerBalanceTransaction]] = {
    db.run {
      query.filter(_.invoiceId.inSet(invoiceIds)).result
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[StripeCustomerBalanceTransaction]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }

  def getAllCustomerBalanceTransactionSources(): Future[Seq[Transaction.Source]] = {
    db.run {
      sql"""
        SELECT id, stripe_account_id, live_mode, customer_id
        FROM stripe.customer_balance_transaction
        WHERE invoice_id IS NULL AND credit_note_id IS NULL;
      """.as[(String, String, Boolean, Option[String])]
    }.map(_.map { case (id, accountId, liveMode, customerId) => database.models.Transaction.Source(id, accountId, liveMode, customerId) })
  }
}

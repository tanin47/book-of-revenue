package database.services

import database.models.{Transaction, TransactionTable, ListableTransaction, RichTransaction}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object TransactionService {
  case class CreateData(
    stripeAccountId: String,
    liveMode: Boolean,
    transactionId: String,
    transactionType: Transaction.Type,
    customerId: Option[String],
    startedAt: Option[Instant],
    batchTimestamp: Instant
  )

  case class Stats(
    count: Long,
    maxUpdatedAt: Option[Instant]
  )
}

@Singleton
class TransactionService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  invoiceService: InvoiceService,
  chargeService: ChargeService,
  paymentIntentService: PaymentIntentService,
  invoiceItemService: InvoiceItemService,
  subscriptionItemService: SubscriptionItemService,
  customerBalanceTransactionService: CustomerBalanceTransactionService,
  creditBalanceTransactionService: CreditBalanceTransactionService,
  customerService: CustomerService,
)(implicit ec: ExecutionContext) extends BaseDbService {
  import TransactionService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[TransactionTable] = TableQuery[TransactionTable]

  def create(data: CreateData): Future[Transaction] = {
    val entity = Transaction(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.transactionId,
      tpe = data.transactionType,
      status = Transaction.Status.Undetermined,
      customerId = data.customerId,
      title = None,
      settlementTotalValue = None,
      settlementCurrency = None,
      startedAt = data.startedAt,
      processedAt = None,
      syncedAt = None,
      batchTimestamp = data.batchTimestamp
    )

    for {
      id <- db.run { query += entity }
    } yield {
      entity
    }
  }

  def createIfNotExist(
    id: String,
    tpe: Transaction.Type,
    stripeAccountId: String,
    liveMode: Boolean,
    customerId: Option[String],
    batchTimestamp: Instant,
  ): Future[Transaction] = {
    def updateAndGet(): Future[Transaction] = {
      updateBatchTimestamp(stripeAccountId, liveMode, id, batchTimestamp).flatMap { _ =>
        getById(stripeAccountId, liveMode, id).map(_.get)
      }
    }

    for {
      existing <- getById(stripeAccountId, liveMode, id)
      created <- existing match {
        case Some(existing) => updateAndGet()
        case None =>
          create(CreateData(
            stripeAccountId = stripeAccountId,
            liveMode = liveMode,
            transactionId = id,
            transactionType = tpe,
            customerId = customerId,
            startedAt = None,
            batchTimestamp = batchTimestamp
          ))
            .recoverWith {
              case e: PSQLException if matchUniqueConstraintException(e, "transaction__id__type") =>
                updateAndGet()
            }
      }
    } yield {
      created
    }
  }

  def updateBatchTimestamp(stripeAccountId: String, liveMode: Boolean, transactionId: String, batchTimestamp: Instant): Future[Unit] = {
    db.run {
      query
        .filter { q => q.id === transactionId && q.stripeAccountId === stripeAccountId && q.liveMode === liveMode }
        .map { q => (q.batchTimestamp, q.processedAt) }
        .update((batchTimestamp, None))
    }
      .map { _ => ()}
  }

  def count(stripeAccountId: String, liveMode: Boolean): Future[Long] = {
    db.run {
      query
        .filter { q =>
          q.stripeAccountId === stripeAccountId &&
            q.liveMode === liveMode
        }
        .size.result
    }
      .map(_.toLong)
  }

  def getAll(stripeAccountId: String, liveMode: Boolean, offset: Int, limit: Int): Future[Seq[Transaction]] = {
    db.run {
      query
        .filter { q =>
          q.stripeAccountId === stripeAccountId &&
            q.liveMode === liveMode &&
            q.processedAt.isDefined
        }
        .sortBy { q =>
          (q.syncedAt.desc, q.processedAt.desc, q.batchTimestamp.desc)
        }
        .drop(offset)
        .take(limit)
        .result
    }
  }


  def getAllListable(stripeAccountId: String, liveMode: Boolean, offset: Int, limit: Int): Future[Seq[ListableTransaction]] = {
    getAll(stripeAccountId, liveMode, offset, limit).flatMap(hydrateListable)
  }

  def getById(stripeAccountId: String, liveMode: Boolean, transactionId: String): Future[Option[Transaction]] = {
    db.run {
      query.filter { q => q.id === transactionId && q.stripeAccountId === stripeAccountId && q.liveMode === liveMode }.result.headOption
    }
  }

  def getRichById(stripeAccountId: String, liveMode: Boolean, transactionId: String): Future[Option[RichTransaction]] = {
    getById(stripeAccountId, liveMode, transactionId).flatMap { transaction => hydrate(transaction.toSeq) }.map(_.headOption)
  }

  private[this] def hydrate(transactions: Seq[Transaction]): Future[Seq[RichTransaction]] = {
    def idsOf(tpe: Transaction.Type): Set[String] =
      transactions.filter(_.tpe == tpe).map(_.id).toSet

    for {
      invoices <- invoiceService.getRichByIds(idsOf(Transaction.Type.Invoice))
      charges <- chargeService.getRichByIds(idsOf(Transaction.Type.StandaloneCharge))
      paymentIntents <- paymentIntentService.getRichByIds(idsOf(Transaction.Type.StandalonePaymentIntent))
      invoiceItems <- invoiceItemService.getRichByIds(idsOf(Transaction.Type.UnbilledInvoiceItem))
      subscriptionItems <- Future.sequence(idsOf(Transaction.Type.UnbilledUsageSubscriptionItem).toSeq.map(subscriptionItemService.getRichById)).map(_.flatten)
      customerBalanceTransactions <- customerBalanceTransactionService.getByIds(idsOf(Transaction.Type.StandaloneCustomerBalanceTransaction))
      creditBalanceTransactions <- creditBalanceTransactionService.getRichByIds(idsOf(Transaction.Type.StandaloneCreditBalanceTransaction))
      customers <- customerService.getByIds(transactions.flatMap(_.customerId).toSet)
    } yield {
      val invoicesById = invoices.map { i => i.base.id -> i }.toMap
      val chargesById = charges.map { c => c.base.id -> c }.toMap
      val paymentIntentsById = paymentIntents.map { p => p.base.id -> p }.toMap
      val invoiceItemsById = invoiceItems.map { i => i.base.id -> i }.toMap
      val subscriptionItemsById = subscriptionItems.map { s => s.base.id -> s }.toMap
      val customerBalanceTransactionsById = customerBalanceTransactions.map { t => t.id -> t }.toMap
      val creditBalanceTransactionsById = creditBalanceTransactions.map { t => t.base.id -> t }.toMap
      val customersById = customers.map { c => c.id -> c }.toMap

      transactions.map { transaction =>
        val id = transaction.id
        RichTransaction(
          base = transaction,
          customer = transaction.customerId.flatMap(customersById.get),
          invoice = invoicesById.get(id),
          charge = chargesById.get(id),
          paymentIntent = paymentIntentsById.get(id),
          invoiceItem = invoiceItemsById.get(id),
          subscriptionItem = subscriptionItemsById.get(id),
          customerBalanceTransaction = customerBalanceTransactionsById.get(id),
          creditBalanceTransaction = creditBalanceTransactionsById.get(id),
        )
      }
    }
  }

  def deleteById(transactionId: String): Future[Unit] = {
    db
      .run { query.filter(_.id === transactionId).delete }
      .map { _ => () }
  }

  def getOutdateds(currentBatchTimestamp: Instant, limit: Int): Future[Seq[Transaction]] = {
    db.run {
      query
        .filter { q => q.batchTimestamp < currentBatchTimestamp }
        .take(limit)
        .result
    }
  }

  private[this] def hydrateListable(transactions: Seq[Transaction]): Future[Seq[ListableTransaction]] = {
    for {
      customers <- customerService.getByIds(transactions.flatMap(_.customerId).toSet)
    } yield {
      val customersById = customers.map { c => c.id -> c }.toMap

      transactions.map { transaction =>
        ListableTransaction(
          base = transaction,
          customer = transaction.customerId.flatMap(customersById.get),
        )
      }
    }
  }

  def getUpdateAction(
    transactionId: String,
    tpe: Transaction.Type,
    status: Transaction.Status,
    startedAt: Option[Instant],
    processedAt: Option[Instant],
    journalEntriesGeneratedAt: Option[Instant],
    title: Option[String],
    settlementTotalValue: Option[Long],
    settlementCurrency: Option[String],
  ): DBIOAction[Int, NoStream, Effect.Write] = {
    query
      .filter { q => q.id === transactionId  && q.tpe === tpe }
      .map { q =>
        (
          q.status,
          q.startedAt,
          q.processedAt,
          q.syncedAt,
          q.title,
          q.settlementTotalValue,
          q.settlementCurrency,
        )
      }
      .update((
        status,
        startedAt,
        processedAt,
        journalEntriesGeneratedAt,
        title,
        settlementTotalValue,
        settlementCurrency,
      ))
  }

  def updateProcessedAt(transactionId: String, tpe: Transaction.Type, processedAt: Option[Instant]): Future[Unit] = {
    db
      .run {
        query
          .filter { q => q.id === transactionId && q.tpe === tpe }
          .map(_.processedAt)
          .update(processedAt)
      }
      .map { _ => ()}
  }

  def getUnprocesseds(batchTimestamp: Instant, limit: Int): Future[Seq[Transaction]] = {
    db.run {
      query
        .filter { q => q.processedAt.isEmpty && q.batchTimestamp === batchTimestamp }
        .sortBy(_.id)
        .take(limit)
        .result
    }
  }

  def getStats(stripeAccountId: String, liveMode: Boolean): Future[Stats] = {
    db.run {
      sql"""
        SELECT COUNT(*) AS count, MAX(processed_at) AS max_processed_at
        FROM transaction
        WHERE stripe_account_id = $stripeAccountId AND live_mode = $liveMode;
      """.as[(Option[Long], Option[Instant])]
    }
      .map { items =>
        items
          .headOption
          .map { case (count, maxProcessedAt) => Stats(count.getOrElse(0L), maxProcessedAt) }
          .getOrElse(Stats(0L, None))
      }
  }

}

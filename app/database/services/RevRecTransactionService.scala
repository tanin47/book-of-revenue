package database.services

import database.models.{RevRecTransaction, RevRecTransactionTable, ListableRevRecTransaction, RichRevRecTransaction}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object RevRecTransactionService {
  case class CreateData(
    stripeAccountId: String,
    liveMode: Boolean,
    transactionId: String,
    revRecTransactionType: RevRecTransaction.Type,
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
class RevRecTransactionService @Inject() (
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
  import RevRecTransactionService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[RevRecTransactionTable] = TableQuery[RevRecTransactionTable]

  def create(data: CreateData): Future[RevRecTransaction] = {
    val entity = RevRecTransaction(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.transactionId,
      tpe = data.revRecTransactionType,
      status = RevRecTransaction.Status.Undetermined,
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
    tpe: RevRecTransaction.Type,
    stripeAccountId: String,
    liveMode: Boolean,
    customerId: Option[String],
    batchTimestamp: Instant,
  ): Future[RevRecTransaction] = {
    def updateAndGet(): Future[RevRecTransaction] = {
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
            revRecTransactionType = tpe,
            customerId = customerId,
            startedAt = None,
            batchTimestamp = batchTimestamp
          ))
            .recoverWith {
              case e: PSQLException if matchUniqueConstraintException(e, "rev_rec_transaction__id__type") =>
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

  def getAll(stripeAccountId: String, liveMode: Boolean, offset: Int, limit: Int): Future[Seq[RevRecTransaction]] = {
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


  def getAllListable(stripeAccountId: String, liveMode: Boolean, offset: Int, limit: Int): Future[Seq[ListableRevRecTransaction]] = {
    getAll(stripeAccountId, liveMode, offset, limit).flatMap(hydrateListable)
  }

  def getById(stripeAccountId: String, liveMode: Boolean, transactionId: String): Future[Option[RevRecTransaction]] = {
    db.run {
      query.filter { q => q.id === transactionId && q.stripeAccountId === stripeAccountId && q.liveMode === liveMode }.result.headOption
    }
  }

  def getRichById(stripeAccountId: String, liveMode: Boolean, transactionId: String): Future[Option[RichRevRecTransaction]] = {
    getById(stripeAccountId, liveMode, transactionId).flatMap { transaction => hydrate(transaction.toSeq) }.map(_.headOption)
  }

  private[this] def hydrate(transactions: Seq[RevRecTransaction]): Future[Seq[RichRevRecTransaction]] = {
    def idsOf(tpe: RevRecTransaction.Type): Set[String] =
      transactions.filter(_.tpe == tpe).map(_.id).toSet

    for {
      invoices <- invoiceService.getRichByIds(idsOf(RevRecTransaction.Type.Invoice))
      charges <- chargeService.getRichByIds(idsOf(RevRecTransaction.Type.StandaloneCharge))
      paymentIntents <- paymentIntentService.getRichByIds(idsOf(RevRecTransaction.Type.StandalonePaymentIntent))
      invoiceItems <- invoiceItemService.getRichByIds(idsOf(RevRecTransaction.Type.UnbilledInvoiceItem))
      subscriptionItems <- Future.sequence(idsOf(RevRecTransaction.Type.UnbilledUsageSubscriptionItem).toSeq.map(subscriptionItemService.getRichById)).map(_.flatten)
      customerBalanceTransactions <- customerBalanceTransactionService.getByIds(idsOf(RevRecTransaction.Type.StandaloneCustomerBalanceTransaction))
      creditBalanceTransactions <- creditBalanceTransactionService.getRichByIds(idsOf(RevRecTransaction.Type.StandaloneCreditBalanceTransaction))
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
        RichRevRecTransaction(
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

  def getOutdateds(currentBatchTimestamp: Instant, limit: Int): Future[Seq[RevRecTransaction]] = {
    db.run {
      query
        .filter { q => q.batchTimestamp < currentBatchTimestamp }
        .take(limit)
        .result
    }
  }

  private[this] def hydrateListable(transactions: Seq[RevRecTransaction]): Future[Seq[ListableRevRecTransaction]] = {
    for {
      customers <- customerService.getByIds(transactions.flatMap(_.customerId).toSet)
    } yield {
      val customersById = customers.map { c => c.id -> c }.toMap

      transactions.map { transaction =>
        ListableRevRecTransaction(
          base = transaction,
          customer = transaction.customerId.flatMap(customersById.get),
        )
      }
    }
  }

  def getUpdateAction(
    transactionId: String,
    tpe: RevRecTransaction.Type,
    status: RevRecTransaction.Status,
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

  def updateProcessedAt(transactionId: String, tpe: RevRecTransaction.Type, processedAt: Option[Instant]): Future[Unit] = {
    db
      .run {
        query
          .filter { q => q.id === transactionId && q.tpe === tpe }
          .map(_.processedAt)
          .update(processedAt)
      }
      .map { _ => ()}
  }

  def getUnprocesseds(batchTimestamp: Instant, limit: Int): Future[Seq[RevRecTransaction]] = {
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
        FROM rev_rec_transaction
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

package database.services

import database.models.Transaction.Source
import database.models.metronome.{MetronomeDraftInvoice, MetronomeDraftInvoiceTable, RichMetronomeBreakdownDraftInvoice, RichMetronomeDraftInvoice}
import framework.{BaseDbService, Instant}
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MetronomeDraftInvoiceService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  metronomeDraftLineItemService: MetronomeDraftLineItemService,
  metronomeBreakdownDraftInvoiceService: MetronomeBreakdownDraftInvoiceService,
  stripeAccountService: StripeAccountService
)(implicit ec: ExecutionContext) extends BaseDbService {

  import framework.PostgresProfile.api.*

  val query: TableQuery[MetronomeDraftInvoiceTable] = TableQuery[MetronomeDraftInvoiceTable]

  def create(entity: MetronomeDraftInvoice): Future[MetronomeDraftInvoice] = {
    db
      .run { query += entity }
      .map{ _ => entity }
  }

  def getMaxSnapshotTime(): Future[Option[Instant]] = {
    db.run {
      query.map(_.snapshotTime).max.result
    }
  }

  def getById(id: String): Future[Option[MetronomeDraftInvoice]] = {
    for {
      maxSnapshotTime <- getMaxSnapshotTime()
      invoice <- db.run {
        query.filter { q => q.id === id && q.snapshotTime === maxSnapshotTime }.result.headOption
      }
    } yield {
      invoice
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[MetronomeDraftInvoice]] = {
    for {
      maxSnapshotTime <- getMaxSnapshotTime()
      invoices <- db.run {
        query.filter { q => q.id.inSet(ids) && q.snapshotTime === maxSnapshotTime }.result
      }
    } yield {
      invoices
    }
  }

  def getByCustomerId(customerId: String): Future[Seq[MetronomeDraftInvoice]] = {
    for {
      maxSnapshotTime <- getMaxSnapshotTime()
      invoices <- db.run {
        query.filter { q => q.customerId === customerId && q.snapshotTime === maxSnapshotTime }.result
      }
    } yield {
      invoices
    }
  }

  def getRichById(id: String): Future[Option[RichMetronomeDraftInvoice]] = {
    getById(id).flatMap { item => hydrate(item.toList) }.map(_.headOption)
  }

  def getRichByIds(ids: Set[String]): Future[Seq[RichMetronomeDraftInvoice]] = {
    getByIds(ids).flatMap { items => hydrate(items.toList) }
  }

  def getRichByCustomerId(customerId: String): Future[Seq[RichMetronomeDraftInvoice]] = {
    getByCustomerId(customerId).flatMap { items => hydrate(items.toList) }
  }

  def getAllTransactionSources(): Future[Seq[Source]] = {
    for {
      maxSnapshotTime <- getMaxSnapshotTime()
      stripeAccount <- stripeAccountService.getAll().map(_.head)
      sources <- db.run {
        sql"""
          SELECT
            d.id, d.environment_type = 'PRODUCTION' AS liveMode, null AS customer_id
          FROM metronome.draft_invoice d
          LEFT JOIN metronome.invoice i ON i.id = d.id
          LEFT JOIN stripe.invoice si ON si.id = i.billing_provider_invoice_id
          WHERE si.id IS NULL
        """.as[(String, Boolean, Option[String])]
      }.map(_.map { case (id, liveMode, customerId) => Source(id, stripeAccount.id, liveMode, customerId) })
    } yield {
      sources
    }
  }

  private[this] def hydrate(items: List[MetronomeDraftInvoice]): Future[Seq[RichMetronomeDraftInvoice]] = {
    val invoiceIds = items.map(_.id).toSet

    for {
      lineItems <- metronomeDraftLineItemService.getByInvoiceIds(invoiceIds)
      breakdownInvoices <- metronomeBreakdownDraftInvoiceService.getRichByInvoiceIds(invoiceIds)
    } yield {
      val lineItemsByInvoice = lineItems.groupBy(_.invoiceId)
      val breakdownInvoiceByInvoice = breakdownInvoices.groupBy(_.base.invoiceId.get).view.mapValues(_.head).toMap

      items.map { item =>
        RichMetronomeDraftInvoice(
          base = item,
          lineItems = lineItemsByInvoice
            .getOrElse(Some(item.id), Seq.empty)
            .sortBy { lineItem => (lineItem.startingAt.map(_.toEpochMilli), lineItem.id) },
          breakdownInvoice = breakdownInvoiceByInvoice.get(item.id),
        )
      }
    }
  }

}

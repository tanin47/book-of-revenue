package database.services

import database.models.metronome.{MetronomeBreakdownDraftInvoice, MetronomeBreakdownDraftInvoiceTable, RichMetronomeBreakdownDraftInvoice}
import framework.{BaseDbService, Instant}
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MetronomeBreakdownDraftInvoiceService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  metronomeBreakdownDraftLineItemService: MetronomeBreakdownDraftLineItemService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import framework.PostgresProfile.api.*

  val query: TableQuery[MetronomeBreakdownDraftInvoiceTable] = TableQuery[MetronomeBreakdownDraftInvoiceTable]

  def getMaxSnapshotTimestamp(): Future[Option[Instant]] = {
    db.run {
      query.map(_.snapshotTimestamp).max.result
    }
  }

  def getById(id: String): Future[Option[MetronomeBreakdownDraftInvoice]] = {
    for {
      maxSnapshotTimestamp <- getMaxSnapshotTimestamp()
      invoice <- db.run {
        query.filter { q => q.id === id && q.snapshotTimestamp === maxSnapshotTimestamp }.result.headOption
      }
    } yield {
      invoice
    }
  }

  def getByInvoiceIds(invoiceIds: Set[String]): Future[Seq[MetronomeBreakdownDraftInvoice]] = {
    for {
      maxSnapshotTimestamp <- getMaxSnapshotTimestamp()
      invoices <- db.run {
        query.filter { q => q.invoiceId.inSet(invoiceIds) && q.snapshotTimestamp === maxSnapshotTimestamp }.result
      }
    } yield {
      invoices
    }
  }

  def getRichById(id: String): Future[Option[RichMetronomeBreakdownDraftInvoice]] = {
    getById(id).flatMap { item => hydrate(item.toList) }.map(_.headOption)
  }

  def getRichByInvoiceIds(invoiceIds: Set[String]): Future[Seq[RichMetronomeBreakdownDraftInvoice]] = {
    getByInvoiceIds(invoiceIds).flatMap { items => hydrate(items.toList) }
  }

  private[this] def hydrate(items: List[MetronomeBreakdownDraftInvoice]): Future[Seq[RichMetronomeBreakdownDraftInvoice]] = {
    for {
      lineItems <- metronomeBreakdownDraftLineItemService.getByInvoiceBreakdownIds(items.map(_.id).toSet)
    } yield {
      val lineItemsByBreakdown = lineItems.groupBy(_.invoiceBreakdownId)

      items.map { item =>
        RichMetronomeBreakdownDraftInvoice(
          base = item,
          lineItems = lineItemsByBreakdown
            .getOrElse(Some(item.id), Seq.empty)
            .sortBy { lineItem => (lineItem.breakdownStartTimestamp.map(_.toEpochMilli), lineItem.id) },
        )
      }
    }
  }
}

package database.services

import database.models.metronome.{MetronomeBreakdownInvoice, MetronomeBreakdownInvoiceTable, RichMetronomeBreakdownInvoice}
import framework.{BaseDbService, Instant}
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MetronomeBreakdownInvoiceService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  metronomeBreakdownLineItemService: MetronomeBreakdownLineItemService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import framework.PostgresProfile.api.*

  val query: TableQuery[MetronomeBreakdownInvoiceTable] = TableQuery[MetronomeBreakdownInvoiceTable]

  def getById(id: String): Future[Option[MetronomeBreakdownInvoice]] = {
    for {
      maxSnapshotTimestamp <- getMaxSnapshotTimestamp()
      invoice <- db.run {
        query.filter { q => q.id === id && q.snapshotTimestamp === maxSnapshotTimestamp }.result.headOption
      }
    } yield {
      invoice
    }
  }

  def getMaxSnapshotTimestamp(): Future[Option[Instant]] = {
    db.run { query.map(_.snapshotTimestamp).max.result }
  }

  def getByInvoiceIds(invoiceIds: Set[String]): Future[Seq[MetronomeBreakdownInvoice]] = {
    for {
      maxSnapshotTimestamp <- getMaxSnapshotTimestamp()
      invoices <- db.run {
        query.filter { q => q.invoiceId.inSet(invoiceIds) && q.snapshotTimestamp === maxSnapshotTimestamp }.result
      }
    } yield {
      invoices
    }
  }

  def getRichById(id: String): Future[Option[RichMetronomeBreakdownInvoice]] = {
    getById(id).flatMap { item => hydrate(item.toList) }.map(_.headOption)
  }

  def getRichByInvoiceIds(invoiceIds: Set[String]): Future[Seq[RichMetronomeBreakdownInvoice]] = {
    getByInvoiceIds(invoiceIds).flatMap { items => hydrate(items.toList) }
  }

  private[this] def hydrate(items: List[MetronomeBreakdownInvoice]): Future[Seq[RichMetronomeBreakdownInvoice]] = {
    for {
      lineItems <- metronomeBreakdownLineItemService.getByInvoiceBreakdownIds(items.map(_.id).toSet)
    } yield {
      val lineItemsByBreakdown = lineItems.groupBy(_.invoiceBreakdownId)

      items.map { item =>
        RichMetronomeBreakdownInvoice(
          base = item,
          lineItems = lineItemsByBreakdown
            .getOrElse(Some(item.id), Seq.empty)
            .sortBy { lineItem => (lineItem.breakdownStartTimestamp.map(_.toEpochMilli), lineItem.id) },
        )
      }
    }
  }
}

package database.services

import database.models.metronome.{MetronomeBreakdownDraftLineItem, MetronomeBreakdownDraftLineItemTable}
import framework.{BaseDbService, Instant}
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MetronomeBreakdownDraftLineItemService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import framework.PostgresProfile.api.*

  val query: TableQuery[MetronomeBreakdownDraftLineItemTable] = TableQuery[MetronomeBreakdownDraftLineItemTable]

  def getMaxSnapshotTimestamp(): Future[Option[Instant]] = {
    db.run {
      query.map(_.snapshotTimestamp).max.result
    }
  }

  def getById(id: String): Future[Option[MetronomeBreakdownDraftLineItem]] = {
    for {
      maxSnapshotTimestamp <- getMaxSnapshotTimestamp()
      lineItem <- db.run {
        query.filter { q => q.id === id && q.snapshotTimestamp === maxSnapshotTimestamp }.result.headOption
      }
    } yield {
      lineItem
    }
  }

  def getByInvoiceBreakdownIds(invoiceBreakdownIds: Set[String]): Future[Seq[MetronomeBreakdownDraftLineItem]] = {
    for {
      maxSnapshotTimestamp <- getMaxSnapshotTimestamp()
      lineItems <- db.run {
        query.filter { q => q.invoiceBreakdownId.inSet(invoiceBreakdownIds) && q.snapshotTimestamp === maxSnapshotTimestamp }.result
      }
    } yield {
      lineItems
    }
  }
}

package database.services

import database.models.metronome.{MetronomeDraftLineItem, MetronomeDraftLineItemTable}
import framework.{BaseDbService, Instant}
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MetronomeDraftLineItemService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import framework.PostgresProfile.api.*

  val query: TableQuery[MetronomeDraftLineItemTable] = TableQuery[MetronomeDraftLineItemTable]

  def getMaxSnapshotTime(): Future[Option[Instant]] = {
    db.run {
      query.map(_.snapshotTime).max.result
    }
  }

  def getById(id: String): Future[Option[MetronomeDraftLineItem]] = {
    for {
      maxSnapshotTime <- getMaxSnapshotTime()
      lineItem <- db.run {
        query.filter { q => q.id === id && q.snapshotTime === maxSnapshotTime }.result.headOption
      }
    } yield {
      lineItem
    }
  }

  def getByInvoiceIds(invoiceIds: Set[String]): Future[Seq[MetronomeDraftLineItem]] = {
    for {
      maxSnapshotTime <- getMaxSnapshotTime()
      lineItems <- db.run {
        query.filter { q => q.invoiceId.inSet(invoiceIds) && q.snapshotTime === maxSnapshotTime }.result
      }
    } yield {
      lineItems
    }
  }
}

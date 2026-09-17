package database.services

import database.models.metronome.MetronomeInvoice.BillingProviderType
import database.models.metronome.{MetronomeInvoice, MetronomeInvoiceTable, RichMetronomeBreakdownInvoice, RichMetronomeInvoice}
import framework.BaseDbService
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MetronomeInvoiceService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  metronomeLineItemService: MetronomeLineItemService,
  metronomeBreakdownInvoiceService: MetronomeBreakdownInvoiceService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import framework.PostgresProfile.api.*

  val query: TableQuery[MetronomeInvoiceTable] = TableQuery[MetronomeInvoiceTable]

  def create(invoice: MetronomeInvoice): Future[MetronomeInvoice] = {
    db
      .run { query += invoice }
      .map { _ => invoice }
  }

  def getById(id: String): Future[Option[MetronomeInvoice]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[MetronomeInvoice]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }

  def getByCustomerId(customerId: String): Future[Seq[MetronomeInvoice]] = {
    db.run {
      query.filter(_.customerId === customerId).result
    }
  }

  def getByBillingProviderInvoiceId(billingProviderInvoiceId: String, billingProviderType: BillingProviderType): Future[Option[MetronomeInvoice]] = {
    db.run {
      query
        .filter { q => q.billingProviderInvoiceId === billingProviderInvoiceId && q.billingProviderType === billingProviderType.toString }
        .result
        .headOption
    }
  }

  def getRichById(id: String): Future[Option[RichMetronomeInvoice]] = {
    getById(id).flatMap { item => hydrate(item.toList) }.map(_.headOption)
  }

  def getRichByIds(ids: Set[String]): Future[Seq[RichMetronomeInvoice]] = {
    getByIds(ids).flatMap { items => hydrate(items.toList) }
  }

  def getRichByCustomerId(customerId: String): Future[Seq[RichMetronomeInvoice]] = {
    getByCustomerId(customerId).flatMap { items => hydrate(items.toList) }
  }

  def getRichByBillingProviderInvoiceId(billingProviderInvoiceId: String, billingProviderType: BillingProviderType): Future[Option[RichMetronomeInvoice]] = {
    getByBillingProviderInvoiceId(billingProviderInvoiceId, billingProviderType).flatMap { item => hydrate(item.toList) }.map(_.headOption)
  }

  private[this] def hydrate(items: List[MetronomeInvoice]): Future[Seq[RichMetronomeInvoice]] = {
    val invoiceIds = items.map(_.id).toSet

    for {
      lineItems <- metronomeLineItemService.getByInvoiceIds(invoiceIds)
      breakdownInvoices <- metronomeBreakdownInvoiceService.getRichByInvoiceIds(invoiceIds)
    } yield {
      val lineItemsByInvoice = lineItems.groupBy(_.invoiceId)
      val breakdownInvoiceByInvoice = breakdownInvoices.groupBy(_.base.invoiceId.get).view.mapValues(_.head).toMap
      println("OK")
      items.map { item =>
        println("OK2")
        RichMetronomeInvoice(
          base = item,
          lineItems = lineItemsByInvoice
            .getOrElse(Some(item.id), Seq.empty)
            .sortBy { lineItem => (lineItem.startingAt.map(_.toEpochMilli), lineItem.id) },
          breakdownInvoice = breakdownInvoiceByInvoice.get(item.id),
        )
      }
    }
  }

  // An invoice can have several breakdown rows (one per breakdown window, re-exported on every snapshot),
  // so the most recently snapshotted window is the one carried on RichMetronomeInvoice.
  private[this] def latestFirst(breakdown: RichMetronomeBreakdownInvoice): (Option[Long], Option[Long], String) = {
    (
      breakdown.base.snapshotTimestamp.map(_.toEpochMilli),
      breakdown.base.breakdownStartTimestamp.map(_.toEpochMilli),
      breakdown.base.id
    )
  }

}

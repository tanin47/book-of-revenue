package database.services

import database.models.metronome.{MetronomeLineItem, MetronomeLineItemTable}
import framework.BaseDbService
import jooq.generated.metronome.Tables.METRONOME_LINE_ITEM
import org.jooq.CommonTableExpression
import org.jooq.impl.DSL.*
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object MetronomeLineItemService {
  def getMetronomeProductNames(): CommonTableExpression[?] = {
    name("metronome_product_names").as(
      select(METRONOME_LINE_ITEM.PRODUCT_ID.as("id"), METRONOME_LINE_ITEM.NAME.as("name"))
        .distinctOn(METRONOME_LINE_ITEM.PRODUCT_ID)
        .from(METRONOME_LINE_ITEM)
        .where(METRONOME_LINE_ITEM.UNIT_PRICE.isNotNull)
        .orderBy(METRONOME_LINE_ITEM.PRODUCT_ID, METRONOME_LINE_ITEM.UPDATED_AT.desc)
    )
  }
}

@Singleton
class MetronomeLineItemService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import framework.PostgresProfile.api.*

  val query: TableQuery[MetronomeLineItemTable] = TableQuery[MetronomeLineItemTable]

  def getAll(): Future[Seq[MetronomeLineItem]] = {
    db.run {
      query.result
    }
  }

  def getById(id: String): Future[Option[MetronomeLineItem]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[MetronomeLineItem]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }

  def getByInvoiceIds(invoiceIds: Set[String]): Future[Seq[MetronomeLineItem]] = {
    db.run {
      query.filter(_.invoiceId.inSet(invoiceIds)).result
    }
  }
}

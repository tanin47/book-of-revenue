package database.services

import database.models.metronome.{MetronomeLineItem, MetronomeLineItemTable}
import framework.BaseDbService
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

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

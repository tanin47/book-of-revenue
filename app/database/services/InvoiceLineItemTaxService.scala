package database.services

import database.models.{InvoiceLineItemTax, InvoiceLineItemTaxTable}
import framework.{BaseDbService, PlayConfig}
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.JdbcProfile

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object InvoiceLineItemTaxService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    rank: Int,
    invoiceLineItemId: String,
    amount: Long,
    taxBehaviour: String,
    taxRateId: Option[String]
  )
}

@Singleton
class InvoiceLineItemTaxService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import InvoiceLineItemTaxService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[InvoiceLineItemTaxTable] = TableQuery[InvoiceLineItemTaxTable]

  // A tax has no natural id, so we replace all taxes for a given invoice line item.
  def replaceByInvoiceLineItem(invoiceLineItemId: String, taxes: Seq[CreateData]): Future[Seq[InvoiceLineItemTax]] = {
    val entities = taxes.map { tax =>
      InvoiceLineItemTax(
        stripeAccountId = tax.stripeAccountId,
        liveMode = tax.liveMode,
        rank = tax.rank,
        invoiceLineItemId = tax.invoiceLineItemId,
        amount = tax.amount,
        taxBehaviour = tax.taxBehaviour,
        taxRateId = tax.taxRateId
      )
    }

    val action = for {
      _ <- query.filter(_.invoiceLineItemId === invoiceLineItemId).delete
      _ <- query ++= entities
    } yield ()

    db.run(action.transactionally).map(_ => entities)
  }

  def getByInvoiceLineItemIds(invoiceLineItemIds: Set[String]): Future[Seq[InvoiceLineItemTax]] = {
    db.run {
      query.filter(_.invoiceLineItemId.inSet(invoiceLineItemIds)).result
    }
  }
}

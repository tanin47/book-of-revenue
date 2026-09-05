package database.services

import database.models.{InvoiceLineItemDiscountAmount, InvoiceLineItemDiscountAmountTable}
import framework.{BaseDbService, PlayConfig}
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.JdbcProfile

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object InvoiceLineItemDiscountAmountService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    rank: Int,
    invoiceLineItemId: String,
    amount: Long,
    discountId: String
  )
}

@Singleton
class InvoiceLineItemDiscountAmountService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import InvoiceLineItemDiscountAmountService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[InvoiceLineItemDiscountAmountTable] = TableQuery[InvoiceLineItemDiscountAmountTable]

  // A discount amount has no natural id, so we replace all discount amounts for a given invoice line item.
  def replaceByInvoiceLineItem(invoiceLineItemId: String, discountAmounts: Seq[CreateData]): Future[Seq[InvoiceLineItemDiscountAmount]] = {
    val entities = discountAmounts.map { discountAmount =>
      InvoiceLineItemDiscountAmount(
        stripeAccountId = discountAmount.stripeAccountId,
        liveMode = discountAmount.liveMode,
        rank = discountAmount.rank,
        invoiceLineItemId = discountAmount.invoiceLineItemId,
        amount = discountAmount.amount,
        discountId = discountAmount.discountId
      )
    }

    val action = for {
      _ <- query.filter(_.invoiceLineItemId === invoiceLineItemId).delete
      _ <- query ++= entities
    } yield ()

    db.run(action.transactionally).map(_ => entities)
  }

  def getByInvoiceLineItemIds(invoiceLineItemIds: Set[String]): Future[Seq[InvoiceLineItemDiscountAmount]] = {
    db.run {
      query.filter(_.invoiceLineItemId.inSet(invoiceLineItemIds)).result
    }
  }
}

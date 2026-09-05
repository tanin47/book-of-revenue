package database.services

import database.models.{InvoiceLineItemPretaxCreditAmount, InvoiceLineItemPretaxCreditAmountTable, RichInvoiceLineItemPretaxCreditAmount}
import framework.{BaseDbService, PlayConfig}
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.JdbcProfile

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object InvoiceLineItemPretaxCreditAmountService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    rank: Int,
    invoiceLineItemId: String,
    amount: Long,
    discountId: Option[String],
    creditBalanceTransactionId: Option[String],
    `type`: String
  )
}

@Singleton
class InvoiceLineItemPretaxCreditAmountService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  discountService: DiscountService,
  creditBalanceTransactionService: CreditBalanceTransactionService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import InvoiceLineItemPretaxCreditAmountService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[InvoiceLineItemPretaxCreditAmountTable] = TableQuery[InvoiceLineItemPretaxCreditAmountTable]

  // A pretax credit amount has no natural id, so we replace all of them for a given invoice line item.
  def replaceByInvoiceLineItem(invoiceLineItemId: String, pretaxCreditAmounts: Seq[CreateData]): Future[Seq[InvoiceLineItemPretaxCreditAmount]] = {
    val entities = pretaxCreditAmounts.map { pretaxCreditAmount =>
      InvoiceLineItemPretaxCreditAmount(
        stripeAccountId = pretaxCreditAmount.stripeAccountId,
        liveMode = pretaxCreditAmount.liveMode,
        rank = pretaxCreditAmount.rank,
        invoiceLineItemId = pretaxCreditAmount.invoiceLineItemId,
        amount = pretaxCreditAmount.amount,
        discountId = pretaxCreditAmount.discountId,
        creditBalanceTransactionId = pretaxCreditAmount.creditBalanceTransactionId,
        `type` = pretaxCreditAmount.`type`
      )
    }

    val action = for {
      _ <- query.filter(_.invoiceLineItemId === invoiceLineItemId).delete
      _ <- query ++= entities
    } yield ()

    db.run(action.transactionally).map(_ => entities)
  }

  def getByInvoiceLineItemIds(invoiceLineItemIds: Set[String]): Future[Seq[InvoiceLineItemPretaxCreditAmount]] = {
    db.run {
      query.filter(_.invoiceLineItemId.inSet(invoiceLineItemIds)).result
    }
  }

  def getRichByInvoiceLineItemIds(invoiceLineItemIds: Set[String]): Future[Seq[RichInvoiceLineItemPretaxCreditAmount]] = {
    getByInvoiceLineItemIds(invoiceLineItemIds).flatMap(hydrate)
  }

  private[this] def hydrate(items: Seq[InvoiceLineItemPretaxCreditAmount]): Future[Seq[RichInvoiceLineItemPretaxCreditAmount]] = {
    for {
      discounts <- discountService.getByIds(items.flatMap(_.discountId).toSet)
      creditBalanceTransactions <- creditBalanceTransactionService.getRichByIds(items.flatMap(_.creditBalanceTransactionId).toSet)
      discountsById = discounts.map { d => d.id -> d }.toMap
      creditBalanceTransactionsById = creditBalanceTransactions.map { t => t.base.id -> t }.toMap
    } yield {
      items.map { item =>
        RichInvoiceLineItemPretaxCreditAmount(
          base = item,
          discount = item.discountId.flatMap(discountsById.get),
          creditBalanceTransaction = item.creditBalanceTransactionId.flatMap(creditBalanceTransactionsById.get)
        )
      }
    }
  }
}

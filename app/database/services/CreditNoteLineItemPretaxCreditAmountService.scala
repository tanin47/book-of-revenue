package database.services

import database.models.stripe.{StripeCreditNoteLineItemPretaxCreditAmount, StripeCreditNoteLineItemPretaxCreditAmountTable, RichStripeCreditNoteLineItemPretaxCreditAmount}
import framework.{BaseDbService, PlayConfig}
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object CreditNoteLineItemPretaxCreditAmountService {
  case class CreateData(
    rank: Int,
    creditNoteLineItemId: String,
    amount: Long,
    discountId: Option[String],
    creditBalanceTransactionId: Option[String],
    `type`: String
  )
}

@Singleton
class CreditNoteLineItemPretaxCreditAmountService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  discountService: DiscountService,
  creditBalanceTransactionService: CreditBalanceTransactionService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import CreditNoteLineItemPretaxCreditAmountService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[StripeCreditNoteLineItemPretaxCreditAmountTable] = TableQuery[StripeCreditNoteLineItemPretaxCreditAmountTable]

  // A pretax credit amount has no natural id, so we replace all of them for a given credit note line item.
  def replaceByCreditNoteLineItem(creditNoteLineItemId: String, pretaxCreditAmounts: Seq[CreateData]): Future[Seq[StripeCreditNoteLineItemPretaxCreditAmount]] = {
    val entities = pretaxCreditAmounts.map { pretaxCreditAmount =>
      StripeCreditNoteLineItemPretaxCreditAmount(
        rank = pretaxCreditAmount.rank,
        creditNoteLineItemId = pretaxCreditAmount.creditNoteLineItemId,
        amount = pretaxCreditAmount.amount,
        discountId = pretaxCreditAmount.discountId,
        creditBalanceTransactionId = pretaxCreditAmount.creditBalanceTransactionId,
        `type` = pretaxCreditAmount.`type`
      )
    }

    val action = for {
      _ <- query.filter(_.invoiceLineItemId === creditNoteLineItemId).delete
      _ <- query ++= entities
    } yield ()

    db.run(action.transactionally).map(_ => entities)
  }

  def getByCreditNoteLineItemIds(creditNoteLineItemIds: Set[String]): Future[Seq[StripeCreditNoteLineItemPretaxCreditAmount]] = {
    db.run {
      query.filter(_.invoiceLineItemId.inSet(creditNoteLineItemIds)).result
    }
  }

  def getRichByCreditNoteLineItemIds(creditNoteLineItemIds: Set[String]): Future[Seq[RichStripeCreditNoteLineItemPretaxCreditAmount]] = {
    getByCreditNoteLineItemIds(creditNoteLineItemIds).flatMap(hydrate)
  }

  private[this] def hydrate(items: Seq[StripeCreditNoteLineItemPretaxCreditAmount]): Future[Seq[RichStripeCreditNoteLineItemPretaxCreditAmount]] = {
    for {
      discounts <- discountService.getByIds(items.flatMap(_.discountId).toSet)
      creditBalanceTransactions <- creditBalanceTransactionService.getRichByIds(items.flatMap(_.creditBalanceTransactionId).toSet)
      discountsById = discounts.map { d => d.id -> d }.toMap
      creditBalanceTransactionsById = creditBalanceTransactions.map { t => t.base.id -> t }.toMap
    } yield {
      items.map { item =>
        RichStripeCreditNoteLineItemPretaxCreditAmount(
          base = item,
          discount = item.discountId.flatMap(discountsById.get),
          creditBalanceTransaction = item.creditBalanceTransactionId.flatMap(creditBalanceTransactionsById.get)
        )
      }
    }
  }
}

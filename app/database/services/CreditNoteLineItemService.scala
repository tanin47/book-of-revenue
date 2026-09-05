package database.services

import database.models.{CreditNoteLineItem, CreditNoteLineItemTable, RichCreditNoteLineItem}
import framework.{BaseDbService, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object CreditNoteLineItemService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    creditNoteId: String,
    description: Option[String],
    rank: Int,
    amount: Long,
    `type`: String,
    invoiceLineItemId: Option[String]
  )
}

@Singleton
class CreditNoteLineItemService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  creditNoteLineItemTaxService: CreditNoteLineItemTaxService,
  creditNoteLineItemPretaxCreditAmountService: CreditNoteLineItemPretaxCreditAmountService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import CreditNoteLineItemService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[CreditNoteLineItemTable] = TableQuery[CreditNoteLineItemTable]

  def create(data: CreateData): Future[CreditNoteLineItem] = {
    val entity = CreditNoteLineItem(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      creditNoteId = data.creditNoteId,
      description = data.description,
      rank = data.rank,
      amount = data.amount,
      `type` = data.`type`,
      invoiceLineItemId = data.invoiceLineItemId
    )

    for {
      existing <- getById(entity.id)
      _ <- existing match {
        case Some(_) => update(entity)
        case None =>
          db
            .run { query += entity }
            .recoverWith {
              case e: PSQLException if matchUniqueConstraintException(e, "credit_note_line_item_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: CreditNoteLineItem): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getById(id: String): Future[Option[CreditNoteLineItem]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def getByCreditNoteIds(creditNoteIds: Set[String]): Future[Seq[CreditNoteLineItem]] = {
    db.run {
      query.filter(_.creditNoteId.inSet(creditNoteIds)).result
    }
  }

  def getRichByCreditNoteIds(creditNoteIds: Set[String]): Future[Seq[RichCreditNoteLineItem]] = {
    getByCreditNoteIds(creditNoteIds).flatMap(hydrate)
  }

  private[this] def hydrate(items: Seq[CreditNoteLineItem]): Future[Seq[RichCreditNoteLineItem]] = {
    val lineItemIds = items.map(_.id).toSet

    for {
      taxes <- creditNoteLineItemTaxService.getByCreditNoteLineItemIds(lineItemIds)
      pretaxCreditAmounts <- creditNoteLineItemPretaxCreditAmountService.getRichByCreditNoteLineItemIds(lineItemIds)
    } yield {
      val taxesByLineItem = taxes.groupBy(_.creditNoteLineItemId)
      val pretaxCreditAmountsByLineItem = pretaxCreditAmounts.groupBy(_.base.creditNoteLineItemId)

      items.map { item =>
        RichCreditNoteLineItem(
          base = item,
          pretaxCreditAmounts = pretaxCreditAmountsByLineItem.getOrElse(item.id, Seq.empty).sortBy(_.base.rank),
          taxes = taxesByLineItem.getOrElse(item.id, Seq.empty),
        )
      }
    }
  }
}

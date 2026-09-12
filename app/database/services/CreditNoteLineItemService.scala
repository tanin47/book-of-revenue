package database.services

import database.models.stripe.{StripeCreditNoteLineItem, StripeCreditNoteLineItemTable, RichStripeCreditNoteLineItem}
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

  val query: TableQuery[StripeCreditNoteLineItemTable] = TableQuery[StripeCreditNoteLineItemTable]

  def create(data: CreateData): Future[StripeCreditNoteLineItem] = {
    val entity = StripeCreditNoteLineItem(
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

  def update(entity: StripeCreditNoteLineItem): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getById(id: String): Future[Option[StripeCreditNoteLineItem]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def getByCreditNoteIds(creditNoteIds: Set[String]): Future[Seq[StripeCreditNoteLineItem]] = {
    db.run {
      query.filter(_.creditNoteId.inSet(creditNoteIds)).result
    }
  }

  def getRichByCreditNoteIds(creditNoteIds: Set[String]): Future[Seq[RichStripeCreditNoteLineItem]] = {
    getByCreditNoteIds(creditNoteIds).flatMap(hydrate)
  }

  private[this] def hydrate(items: Seq[StripeCreditNoteLineItem]): Future[Seq[RichStripeCreditNoteLineItem]] = {
    val lineItemIds = items.map(_.id).toSet

    for {
      taxes <- creditNoteLineItemTaxService.getByCreditNoteLineItemIds(lineItemIds)
      pretaxCreditAmounts <- creditNoteLineItemPretaxCreditAmountService.getRichByCreditNoteLineItemIds(lineItemIds)
    } yield {
      val taxesByLineItem = taxes.groupBy(_.creditNoteLineItemId)
      val pretaxCreditAmountsByLineItem = pretaxCreditAmounts.groupBy(_.base.creditNoteLineItemId)

      items.map { item =>
        RichStripeCreditNoteLineItem(
          base = item,
          pretaxCreditAmounts = pretaxCreditAmountsByLineItem.getOrElse(item.id, Seq.empty).sortBy(_.base.rank),
          taxes = taxesByLineItem.getOrElse(item.id, Seq.empty),
        )
      }
    }
  }
}

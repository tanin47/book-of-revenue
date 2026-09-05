package database.services

import database.models.{CreditNoteLineItemTax, CreditNoteLineItemTaxTable}
import framework.{BaseDbService, PlayConfig}
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object CreditNoteLineItemTaxService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    creditNoteLineItemId: String,
    rank: Int,
    amount: Long,
    taxBehavior: String
  )
}

@Singleton
class CreditNoteLineItemTaxService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import CreditNoteLineItemTaxService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[CreditNoteLineItemTaxTable] = TableQuery[CreditNoteLineItemTaxTable]

  // A tax has no natural id, so we replace all taxes for a given credit note line item.
  def replaceByCreditNoteLineItem(creditNoteLineItemId: String, taxes: Seq[CreateData]): Future[Seq[CreditNoteLineItemTax]] = {
    val entities = taxes.map { tax =>
      CreditNoteLineItemTax(
        stripeAccountId = tax.stripeAccountId,
        liveMode = tax.liveMode,
        creditNoteLineItemId = tax.creditNoteLineItemId,
        rank = tax.rank,
        amount = tax.amount,
        taxBehavior = tax.taxBehavior
      )
    }

    val action = for {
      _ <- query.filter(_.creditNoteLineItemId === creditNoteLineItemId).delete
      _ <- query ++= entities
    } yield ()

    db.run(action.transactionally).map(_ => entities)
  }

  def getByCreditNoteLineItemIds(creditNoteLineItemIds: Set[String]): Future[Seq[CreditNoteLineItemTax]] = {
    db.run {
      query.filter(_.creditNoteLineItemId.inSet(creditNoteLineItemIds)).result
    }
  }
}

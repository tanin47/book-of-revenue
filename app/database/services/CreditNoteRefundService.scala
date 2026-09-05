package database.services

import database.models.{CreditNoteRefund, CreditNoteRefundTable}
import framework.{BaseDbService, PlayConfig}
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object CreditNoteRefundService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    creditNoteId: String,
    rank: Int,
    refundId: Option[String],
    `type`: String,
    amountRefunded: Long,
    paymentRecordRefundId: Option[String]
  )
}

@Singleton
class CreditNoteRefundService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import CreditNoteRefundService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[CreditNoteRefundTable] = TableQuery[CreditNoteRefundTable]

  // A credit note refund has no natural id, so we replace all refunds for a given credit note.
  def replaceByCreditNote(creditNoteId: String, refunds: Seq[CreateData]): Future[Seq[CreditNoteRefund]] = {
    val entities = refunds.map { refund =>
      CreditNoteRefund(
        stripeAccountId = refund.stripeAccountId,
        liveMode = refund.liveMode,
        creditNoteId = refund.creditNoteId,
        rank = refund.rank,
        refundId = refund.refundId,
        `type` = refund.`type`,
        amountRefunded = refund.amountRefunded,
        paymentRecordRefundId = refund.paymentRecordRefundId
      )
    }

    val action = for {
      _ <- query.filter(_.creditNoteId === creditNoteId).delete
      _ <- query ++= entities
    } yield ()

    db.run(action.transactionally).map(_ => entities)
  }

  def getByCreditNoteIds(creditNoteIds: Set[String]): Future[Seq[CreditNoteRefund]] = {
    db.run {
      query.filter(_.creditNoteId.inSet(creditNoteIds)).result
    }
  }

  def getByRefundIds(refundIds: Set[String]): Future[Seq[CreditNoteRefund]] = {
    db.run {
      query.filter(_.refundId.inSet(refundIds)).result
    }
  }
}

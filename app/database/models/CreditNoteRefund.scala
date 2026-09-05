package database.models

import framework.Jsonable
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import framework.TransactionDetail.BillingActivity
import slick.lifted.{ProvenShape, Rep}

case class CreditNoteRefund(
  stripeAccountId: String,
  liveMode: Boolean,
  creditNoteId: String,
  rank: Int,
  refundId: Option[String],
  `type`: String,
  amountRefunded: Long,
  paymentRecordRefundId: Option[String],
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> refundId,
    "rank" -> rank,
    "type" -> `type`,
    "amountRefunded" -> amountRefunded,
    "paymentRecordRefundId" -> paymentRecordRefundId,
  )
}

case class RichCreditNoteRefund(
  base: CreditNoteRefund,
  refund: Option[RichRefund]
) extends Jsonable {
  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "refund" -> refund.map(_.toJson()),
  )

  lazy val billingActivities: Seq[BillingActivity.Value] = Seq(
    refund.toList.flatMap(_.billingActivities)
  ).flatten
}

class CreditNoteRefundTable(tag: Tag) extends Table[CreditNoteRefund](tag, "credit_note_refund") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def creditNoteId: Rep[String] = column[String]("credit_note_id")
  def rank: Rep[Int] = column[Int]("rank")
  def refundId: Rep[Option[String]] = column[Option[String]]("refund_id")
  def `type`: Rep[String] = column[String]("type")
  def amountRefunded: Rep[Long] = column[Long]("amount_refunded")
  def paymentRecordRefundId: Rep[Option[String]] = column[Option[String]]("payment_record_refund_id")

  def * : ProvenShape[CreditNoteRefund] = (
    stripeAccountId,
    liveMode,
    creditNoteId,
    rank,
    refundId,
    `type`,
    amountRefunded,
    paymentRecordRefundId
  ).<>((CreditNoteRefund.apply _).tupled, CreditNoteRefund.unapply)
}

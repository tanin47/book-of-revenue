package database.models

import framework.{Instant, Jsonable}
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import framework.TransactionDetail.BillingActivity
import framework.TransactionDetail.BillingActivity.{IssueCreditNote, IssueOutOfBandRefund}
import slick.lifted.{ProvenShape, Rep}

case class CreditNote(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  `type`: String,
  invoiceId: String,
  currency: String,
  total: Long,
  prePaymentAmount: Long,
  customerBalanceTransactionId: Option[String],
  outOfBandAmount: Option[Long],
  createdAt: Instant,
  effectiveAt: Option[Instant],
  voidedAt: Option[Instant],
) extends Jsonable {
  lazy val occurredAt: Instant = effectiveAt.getOrElse(createdAt)

  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "type" -> `type`,
    "invoiceId" -> invoiceId,
    "currency" -> currency,
    "total" -> total,
    "prePaymentAmount" -> prePaymentAmount,
    "customerBalanceTransactionId" -> customerBalanceTransactionId,
    "outOfBandAmount" -> outOfBandAmount,
    "createdAt" -> createdAt.toEpochMilli,
    "effectiveAt" -> effectiveAt.map(_.toEpochMilli),
    "voidedAt" -> voidedAt.map(_.toEpochMilli),
    "occurredAt" -> occurredAt.toEpochMilli,
  )
}

case class RichCreditNote(
  base: CreditNote,
  customerBalanceTransaction: Option[CustomerBalanceTransaction],
  lines: Seq[RichCreditNoteLineItem],
  refunds: Seq[RichCreditNoteRefund],
) extends Jsonable {
  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "customerBalanceTransaction" -> customerBalanceTransaction.map(_.toJson()),
    "lines" -> lines.map(_.toJson()),
    "refunds" -> refunds.map(_.toJson()),
  )

  lazy val billingActivities: Seq[BillingActivity.Value] = Seq(
    customerBalanceTransaction.toList.flatMap(_.billingActivities),
    refunds.flatMap(_.billingActivities),
    base.effectiveAt.toList.flatMap { effectiveAt =>
      Seq(
        Some(IssueCreditNote(effectiveAt, base.id, base.total, base.currency, base.`type` == "post_payment")),
        base.outOfBandAmount.map { outOfBandAmount =>
          IssueOutOfBandRefund(
            effectiveAt,
            outOfBandAmount,
            base.currency
          )
        },
      ).flatten
    },
    base.voidedAt.map { voidedAt => BillingActivity.VoidCreditNote(voidedAt, base.id, base.total, base.currency, base.`type` == "post_payment") }.toSeq,
  ).flatten.sortBy(_.timestamp)
}

class CreditNoteTable(tag: Tag) extends Table[CreditNote](tag, "credit_note") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def `type`: Rep[String] = column[String]("type")
  def invoiceId: Rep[String] = column[String]("invoice_id")
  def currency: Rep[String] = column[String]("currency")
  def total: Rep[Long] = column[Long]("total")
  def prePaymentAmount: Rep[Long] = column[Long]("pre_payment_amount")
  def customerBalanceTransactionId: Rep[Option[String]] = column[Option[String]]("customer_balance_transaction_id")
  def outOfBandAmount: Rep[Option[Long]] = column[Option[Long]]("out_of_band_amount")
  def createdAt: Rep[Instant] = column[Instant]("created_at")
  def effectiveAt: Rep[Option[Instant]] = column[Option[Instant]]("effective_at")
  def voidedAt: Rep[Option[Instant]] = column[Option[Instant]]("voided_at")

  def * : ProvenShape[CreditNote] = (
    stripeAccountId,
    liveMode,
    id,
    `type`,
    invoiceId,
    currency,
    total,
    prePaymentAmount,
    customerBalanceTransactionId,
    outOfBandAmount,
    createdAt,
    effectiveAt,
    voidedAt
  ).<>((CreditNote.apply _).tupled, CreditNote.unapply)
}

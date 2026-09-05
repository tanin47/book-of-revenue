package database.models

import framework.PostgresProfile.api.*
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import framework.TransactionDetail.BillingActivity
import framework.TransactionDetail.BillingActivity.MakePayment
import slick.lifted.{ProvenShape, Rep}

case class InvoicePayment(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  amountPaid: Option[Long],
  amountRequested: Option[Long],
  currency: String,
  invoiceId: String,
  chargeId: Option[String],
  paymentIntentId: Option[String],
  paymentRecordId: Option[String],
  paymentType: Option[String],
  createdAt: Instant,
  canceledAt: Option[Instant],
  paidAt: Option[Instant],
  status: String,
  syncedAt: Instant
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "amountPaid" -> amountPaid,
    "amountRequested" -> amountRequested,
    "currency" -> currency,
    "chargeId" -> chargeId,
    "paymentIntentId" -> paymentIntentId,
    "paymentRecordId" -> paymentRecordId,
    "paymentType" -> paymentType,
    "createdAt" -> createdAt.toEpochMilli,
    "canceledAt" -> canceledAt.map(_.toEpochMilli),
    "paidAt" -> paidAt.map(_.toEpochMilli),
    "status" -> status,
  )
}

case class RichInvoicePayment(
  base: InvoicePayment,
  charge: Option[RichCharge],
  paymentIntent: Option[RichPaymentIntent],
) extends Jsonable {
  lazy val syncedAt: Instant = Seq(Some(base.syncedAt), charge.map(_.syncedAt), paymentIntent.map(_.syncedAt)).flatten.max

  lazy val paidAmount: Long = Seq(charge.map(_.base.amount), paymentIntent.flatMap(_.charge.map(_.base.amount))).flatten.sum

  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "paidAmount" -> paidAmount,
  )

  lazy val billingActivities: Seq[BillingActivity.Value] = Seq(
    base.paidAt.map { p => MakePayment(p, base.chargeId, base.paymentIntentId, base.paymentRecordId, base.amountPaid.getOrElse(0L), base.currency) }.toSeq,
    charge.toList.flatMap(_.contraBillingActivities),
    paymentIntent.toList.flatMap(_.contraBillingActivities)
  ).flatten
}

class InvoicePaymentTable(tag: Tag) extends Table[InvoicePayment](tag, "invoice_payment") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id")
  def amountPaid: Rep[Option[Long]] = column[Option[Long]]("amount_paid")
  def amountRequested: Rep[Option[Long]] = column[Option[Long]]("amount_requested")
  def currency: Rep[String] = column[String]("currency")
  def invoiceId: Rep[String] = column[String]("invoice_id")
  def chargeId: Rep[Option[String]] = column[Option[String]]("charge_id")
  def paymentIntentId: Rep[Option[String]] = column[Option[String]]("payment_intent_id")
  def paymentRecordId: Rep[Option[String]] = column[Option[String]]("payment_record_id")
  def paymentType: Rep[Option[String]] = column[Option[String]]("payment_type")
  def createdAt: Rep[Instant] = column[Instant]("created_at")
  def canceledAt: Rep[Option[Instant]] = column[Option[Instant]]("canceled_at")
  def paidAt: Rep[Option[Instant]] = column[Option[Instant]]("paid_at")
  def status: Rep[String] = column[String]("status")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[InvoicePayment] = (
    stripeAccountId,
    liveMode,
    id,
    amountPaid,
    amountRequested,
    currency,
    invoiceId,
    chargeId,
    paymentIntentId,
    paymentRecordId,
    paymentType,
    createdAt,
    canceledAt,
    paidAt,
    status,
    syncedAt
  ).<>((InvoicePayment.apply _).tupled, InvoicePayment.unapply)
}

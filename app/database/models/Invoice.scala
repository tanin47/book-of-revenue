package database.models

import framework.PostgresProfile.api.*
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import framework.TransactionDetail.BillingActivity
import framework.TransactionDetail.BillingActivity.{FinalizeInvoice, MakePayment, MarkUncollectibleInvoice, VoidInvoice}
import services.ExchangeRate
import slick.lifted.{ProvenShape, Rep}

case class Invoice(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  customerId: String,
  number: Option[String],
  total: Long,
  amountPaid: Long,
  amountOverpaid: Long,
  amountRemaining: Long,
  currency: String,
  finalizedAt: Option[Instant],
  paidAt: Option[Instant],
  dueAt: Option[Instant],
  markedUncollectibleAt: Option[Instant],
  voidedAt: Option[Instant],
  startingBalance: Option[Long],
  endingBalance: Option[Long],
  status: String,
  syncedAt: Instant
) extends Jsonable {
  lazy val appliedCustomerBalance: Long = endingBalance.getOrElse(0L) - startingBalance.getOrElse(0L)

  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "number" -> number,
    "total" -> total,
    "amountPaid" -> amountPaid,
    "amountOverpaid" -> amountOverpaid,
    "amountRemaining" -> amountRemaining,
    "currency" -> currency,
    "status" -> status,
    "finalized_at" -> finalizedAt,
    "paid_at" -> paidAt,
    "due_at" -> dueAt,
    "marked_uncollectible_at" -> markedUncollectibleAt,
    "voided_at" -> voidedAt,
    "applied_customer_balance" -> appliedCustomerBalance,
  )
}

case class RichInvoice(
  base: Invoice,
  lineItems: Seq[RichInvoiceLineItem],
  payments: Seq[RichInvoicePayment],
  customerBalanceTransactions: Seq[CustomerBalanceTransaction],
  creditNotes: Seq[RichCreditNote],
  finalizedAtExchangeRate: Option[ExchangeRate] = None,
) extends Jsonable {
  lazy val syncedAt: Instant = Seq(
    Some(base.syncedAt),
    lineItems.map(_.syncedAt),
    payments.map(_.syncedAt),
    customerBalanceTransactions.map(_.syncedAt)
  ).flatten.max

  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "lineItems" -> lineItems.map(_.toJson()),
    "payments" -> payments.map(_.toJson()),
    "customerBalanceTransactions" -> customerBalanceTransactions.map(_.toJson()),
    "creditNotes" -> creditNotes.map(_.toJson()),
    "billingActivities" -> billingActivities.map(_.toJson())
  )

  lazy val billingActivities: Seq[BillingActivity.Value] = Seq(
    base.finalizedAt.map(FinalizeInvoice.apply).toSeq,
    base.markedUncollectibleAt.map(MarkUncollectibleInvoice.apply).toSeq,
    base.voidedAt.map(VoidInvoice.apply).toSeq,
    customerBalanceTransactions.flatMap(_.billingActivities),
    payments.flatMap(_.billingActivities),
    creditNotes.flatMap(_.billingActivities)
  ).flatten.sorted
}

class InvoiceTable(tag: Tag) extends Table[Invoice](tag, "invoice") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id")
  def customerId: Rep[String] = column[String]("customer_id")
  def number: Rep[Option[String]] = column[Option[String]]("number")
  def total: Rep[Long] = column[Long]("total")
  def amountPaid: Rep[Long] = column[Long]("amount_paid")
  def amountOverpaid: Rep[Long] = column[Long]("amount_overpaid")
  def amountRemaining: Rep[Long] = column[Long]("amount_remaining")
  def currency: Rep[String] = column[String]("currency")
  def finalizedAt: Rep[Option[Instant]] = column[Option[Instant]]("finalized_at")
  def paidAt: Rep[Option[Instant]] = column[Option[Instant]]("paid_at")
  def dueAt: Rep[Option[Instant]] = column[Option[Instant]]("due_at")
  def markedUncollectibleAt: Rep[Option[Instant]] = column[Option[Instant]]("marked_uncollectible_at")
  def voidedAt: Rep[Option[Instant]] = column[Option[Instant]]("voided_at")
  def startingBalance: Rep[Option[Long]] = column[Option[Long]]("starting_balance")
  def endingBalance: Rep[Option[Long]] = column[Option[Long]]("ending_balance")
  def status: Rep[String] = column[String]("status")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[Invoice] = (
    stripeAccountId,
    liveMode,
    id,
    customerId,
    number,
    total,
    amountPaid,
    amountOverpaid,
    amountRemaining,
    currency,
    finalizedAt,
    paidAt,
    dueAt,
    markedUncollectibleAt,
    voidedAt,
    startingBalance,
    endingBalance,
    status,
    syncedAt
  ).<>((Invoice.apply _).tupled, Invoice.unapply)
}

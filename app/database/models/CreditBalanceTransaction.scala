package database.models

import framework.TransactionDetail.BillingActivity
import framework.TransactionDetail.BillingActivity.{CreditCreditBalance, DebitCreditBalance}
import framework.{Instant, Jsonable}
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class CreditBalanceTransaction(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  createdAt: Instant,
  effectiveAt: Instant,
  `type`: Option[String],
  creditGrantId: String,
  creditAmount: Option[Long],
  creditCurrency: Option[String],
  creditType: Option[String],
  creditInvoiceVoidedInvoiceId: Option[String],
  creditInvoiceVoidedInvoiceLineItemId: Option[String],
  debitAmount: Option[Long],
  debitCurrency: Option[String],
  debitType: Option[String],
  debitCreditsAppliedInvoiceId: Option[String],
  debitCreditsAppliedInvoiceLineItemId: Option[String],
  syncedAt: Instant
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "createdAt" -> createdAt.toEpochMilli,
    "effectiveAt" -> effectiveAt.toEpochMilli,
    "type" -> `type`,
    "creditGrantId" -> creditGrantId,
    "creditAmount" -> creditAmount,
    "creditCurrency" -> creditCurrency,
    "creditType" -> creditType,
    "creditInvoiceVoidedInvoiceId" -> creditInvoiceVoidedInvoiceId,
    "creditInvoiceVoidedInvoiceLineItemId" -> creditInvoiceVoidedInvoiceLineItemId,
    "debitAmount" -> debitAmount,
    "debitCurrency" -> debitCurrency,
    "debitType" -> debitType,
    "debitCreditsAppliedInvoiceId" -> debitCreditsAppliedInvoiceId,
    "debitCreditsAppliedInvoiceLineItemId" -> debitCreditsAppliedInvoiceLineItemId,
  )
}

case class RichCreditBalanceTransaction(
  base: CreditBalanceTransaction,
  creditGrant: Option[CreditGrant]
) extends Jsonable {
  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "creditGrant" -> creditGrant.map(_.toJson()),
  )

  lazy val billingActivities: Seq[BillingActivity.Value] = Seq(
    base.`type` match {
      case Some("credit") => CreditCreditBalance(
        timestamp = base.effectiveAt,
        creditBalanceTransactionId = base.id,
        amount = base.creditAmount.get,
        currency = base.creditCurrency.get
      )
      case Some("debit") => DebitCreditBalance(
        timestamp = base.effectiveAt,
        creditBalanceTransactionId = base.id,
        amount = base.debitAmount.get,
        currency = base.debitCurrency.get
      )
      case _ => throw new Exception(s"Unknown credit balance transaction type: ${base.`type`}")
    }
  )
}

class CreditBalanceTransactionTable(tag: Tag) extends Table[CreditBalanceTransaction](tag, "credit_balance_transaction") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def createdAt: Rep[Instant] = column[Instant]("created_at")
  def effectiveAt: Rep[Instant] = column[Instant]("effective_at")
  def `type`: Rep[Option[String]] = column[Option[String]]("type")
  def creditGrantId: Rep[String] = column[String]("credit_grant_id")
  def creditAmount: Rep[Option[Long]] = column[Option[Long]]("credit_amount")
  def creditCurrency: Rep[Option[String]] = column[Option[String]]("credit_currency")
  def creditType: Rep[Option[String]] = column[Option[String]]("credit_type")
  def creditInvoiceVoidedInvoiceId: Rep[Option[String]] = column[Option[String]]("credit_invoice_voided_invoice_id")
  def creditInvoiceVoidedInvoiceLineItemId: Rep[Option[String]] = column[Option[String]]("credit_invoice_voided_invoice_line_item_id")
  def debitAmount: Rep[Option[Long]] = column[Option[Long]]("debit_amount")
  def debitCurrency: Rep[Option[String]] = column[Option[String]]("debit_currency")
  def debitType: Rep[Option[String]] = column[Option[String]]("debit_type")
  def debitCreditsAppliedInvoiceId: Rep[Option[String]] = column[Option[String]]("debit_credits_applied_invoice_id")
  def debitCreditsAppliedInvoiceLineItemId: Rep[Option[String]] = column[Option[String]]("debit_credits_applied_invoice_line_item_id")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[CreditBalanceTransaction] = (
    stripeAccountId,
    liveMode,
    id,
    createdAt,
    effectiveAt,
    `type`,
    creditGrantId,
    creditAmount,
    creditCurrency,
    creditType,
    creditInvoiceVoidedInvoiceId,
    creditInvoiceVoidedInvoiceLineItemId,
    debitAmount,
    debitCurrency,
    debitType,
    debitCreditsAppliedInvoiceId,
    debitCreditsAppliedInvoiceLineItemId,
    syncedAt
  ).<>((CreditBalanceTransaction.apply _).tupled, CreditBalanceTransaction.unapply)
}

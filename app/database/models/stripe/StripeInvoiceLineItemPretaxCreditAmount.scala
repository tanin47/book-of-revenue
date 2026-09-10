package database.models.stripe

import framework.Jsonable
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class StripeInvoiceLineItemPretaxCreditAmount(
  stripeAccountId: String,
  liveMode: Boolean,
  rank: Int,
  invoiceLineItemId: String,
  amount: Long,
  discountId: Option[String],
  creditBalanceTransactionId: Option[String],
  `type`: String
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "rank" -> rank,
    "amount" -> amount,
    "discountId" -> discountId,
    "creditBalanceTransactionId" -> creditBalanceTransactionId,
    "type" -> `type`,
  )
}

case class RichStripeInvoiceLineItemPretaxCreditAmount(
  base: StripeInvoiceLineItemPretaxCreditAmount,
  discount: Option[StripeDiscount],
  creditBalanceTransaction: Option[RichStripeCreditBalanceTransaction],
) extends Jsonable {
  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "discount" -> discount.map(_.toJson()),
    "creditBalanceTransaction" -> creditBalanceTransaction.map(_.toJson()),
  )
}


class StripeInvoiceLineItemPretaxCreditAmountTable(tag: Tag) extends Table[StripeInvoiceLineItemPretaxCreditAmount](tag, Some("stripe"), "invoice_line_item_pretax_credit_amount") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def rank: Rep[Int] = column[Int]("rank")
  def invoiceLineItemId: Rep[String] = column[String]("invoice_line_item_id")
  def amount: Rep[Long] = column[Long]("amount")
  def discountId: Rep[Option[String]] = column[Option[String]]("discount_id")
  def creditBalanceTransactionId: Rep[Option[String]] = column[Option[String]]("credit_balance_transaction_id")
  def `type`: Rep[String] = column[String]("type")

  def * : ProvenShape[StripeInvoiceLineItemPretaxCreditAmount] = (
    stripeAccountId,
    liveMode,
    rank,
    invoiceLineItemId,
    amount,
    discountId,
    creditBalanceTransactionId,
    `type`
  ).<>((StripeInvoiceLineItemPretaxCreditAmount.apply _).tupled, StripeInvoiceLineItemPretaxCreditAmount.unapply)
}

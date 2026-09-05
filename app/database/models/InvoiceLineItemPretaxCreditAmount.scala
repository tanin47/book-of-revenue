package database.models

import framework.Jsonable
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class InvoiceLineItemPretaxCreditAmount(
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

case class RichInvoiceLineItemPretaxCreditAmount(
  base: InvoiceLineItemPretaxCreditAmount,
  discount: Option[Discount],
  creditBalanceTransaction: Option[RichCreditBalanceTransaction],
) extends Jsonable {
  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "discount" -> discount.map(_.toJson()),
    "creditBalanceTransaction" -> creditBalanceTransaction.map(_.toJson()),
  )
}


class InvoiceLineItemPretaxCreditAmountTable(tag: Tag) extends Table[InvoiceLineItemPretaxCreditAmount](tag, "invoice_line_item_pretax_credit_amount") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def rank: Rep[Int] = column[Int]("rank")
  def invoiceLineItemId: Rep[String] = column[String]("invoice_line_item_id")
  def amount: Rep[Long] = column[Long]("amount")
  def discountId: Rep[Option[String]] = column[Option[String]]("discount_id")
  def creditBalanceTransactionId: Rep[Option[String]] = column[Option[String]]("credit_balance_transaction_id")
  def `type`: Rep[String] = column[String]("type")

  def * : ProvenShape[InvoiceLineItemPretaxCreditAmount] = (
    stripeAccountId,
    liveMode,
    rank,
    invoiceLineItemId,
    amount,
    discountId,
    creditBalanceTransactionId,
    `type`
  ).<>((InvoiceLineItemPretaxCreditAmount.apply _).tupled, InvoiceLineItemPretaxCreditAmount.unapply)
}

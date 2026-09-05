package database.models

import framework.PostgresProfile.api.*
import slick.lifted.{ProvenShape, Rep}

case class InvoiceLineItemDiscountAmount(
  stripeAccountId: String,
  liveMode: Boolean,
  rank: Int,
  invoiceLineItemId: String,
  amount: Long,
  discountId: String
)


class InvoiceLineItemDiscountAmountTable(tag: Tag) extends Table[InvoiceLineItemDiscountAmount](tag, "invoice_line_item_discount_amount") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def rank: Rep[Int] = column[Int]("rank")
  def invoiceLineItemId: Rep[String] = column[String]("invoice_line_item_id")
  def amount: Rep[Long] = column[Long]("amount")
  def discountId: Rep[String] = column[String]("discount_id")

  def * : ProvenShape[InvoiceLineItemDiscountAmount] = (
    stripeAccountId,
    liveMode,
    rank,
    invoiceLineItemId,
    amount,
    discountId
  ).<>((InvoiceLineItemDiscountAmount.apply _).tupled, InvoiceLineItemDiscountAmount.unapply)
}

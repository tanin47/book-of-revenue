package database.models

import framework.PostgresProfile.api.*
import slick.lifted.{ProvenShape, Rep}

case class CreditNoteLineItemPretaxCreditAmount(
  rank: Int,
  creditNoteLineItemId: String,
  amount: Long,
  discountId: Option[String],
  creditBalanceTransactionId: Option[String],
  `type`: String
)

case class RichCreditNoteLineItemPretaxCreditAmount(
  base: CreditNoteLineItemPretaxCreditAmount,
  discount: Option[Discount],
  creditBalanceTransaction: Option[RichCreditBalanceTransaction],
)


class CreditNoteLineItemPretaxCreditAmountTable(tag: Tag) extends Table[CreditNoteLineItemPretaxCreditAmount](tag, "credit_note_line_item_pretax_credit_amount") {
  def rank: Rep[Int] = column[Int]("rank")
  def invoiceLineItemId: Rep[String] = column[String]("credit_note_line_item_id")
  def amount: Rep[Long] = column[Long]("amount")
  def discountId: Rep[Option[String]] = column[Option[String]]("discount_id")
  def creditBalanceTransactionId: Rep[Option[String]] = column[Option[String]]("credit_balance_transaction_id")
  def `type`: Rep[String] = column[String]("type")

  def * : ProvenShape[CreditNoteLineItemPretaxCreditAmount] = (
    rank,
    invoiceLineItemId,
    amount,
    discountId,
    creditBalanceTransactionId,
    `type`
  ).<>((CreditNoteLineItemPretaxCreditAmount.apply _).tupled, CreditNoteLineItemPretaxCreditAmount.unapply)
}

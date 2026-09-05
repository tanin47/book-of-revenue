package database.models

import framework.PostgresProfile.api.*
import slick.lifted.{ProvenShape, Rep}

case class CreditNoteLineItemTax(
  stripeAccountId: String,
  liveMode: Boolean,
  creditNoteLineItemId: String,
  rank: Int,
  amount: Long,
  taxBehavior: String
)

class CreditNoteLineItemTaxTable(tag: Tag) extends Table[CreditNoteLineItemTax](tag, "credit_note_line_item_tax") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def creditNoteLineItemId: Rep[String] = column[String]("credit_note_line_item_id")
  def rank: Rep[Int] = column[Int]("rank")
  def amount: Rep[Long] = column[Long]("amount")
  def taxBehavior: Rep[String] = column[String]("tax_behavior")

  def * : ProvenShape[CreditNoteLineItemTax] = (
    stripeAccountId,
    liveMode,
    creditNoteLineItemId,
    rank,
    amount,
    taxBehavior
  ).<>((CreditNoteLineItemTax.apply _).tupled, CreditNoteLineItemTax.unapply)
}

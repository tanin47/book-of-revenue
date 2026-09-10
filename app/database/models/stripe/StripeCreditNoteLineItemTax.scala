package database.models.stripe

import framework.PostgresProfile.api.*
import slick.lifted.{ProvenShape, Rep}

case class StripeCreditNoteLineItemTax(
  stripeAccountId: String,
  liveMode: Boolean,
  creditNoteLineItemId: String,
  rank: Int,
  amount: Long,
  taxBehavior: String
)

class StripeCreditNoteLineItemTaxTable(tag: Tag) extends Table[StripeCreditNoteLineItemTax](tag, Some("stripe"), "credit_note_line_item_tax") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def creditNoteLineItemId: Rep[String] = column[String]("credit_note_line_item_id")
  def rank: Rep[Int] = column[Int]("rank")
  def amount: Rep[Long] = column[Long]("amount")
  def taxBehavior: Rep[String] = column[String]("tax_behavior")

  def * : ProvenShape[StripeCreditNoteLineItemTax] = (
    stripeAccountId,
    liveMode,
    creditNoteLineItemId,
    rank,
    amount,
    taxBehavior
  ).<>((StripeCreditNoteLineItemTax.apply _).tupled, StripeCreditNoteLineItemTax.unapply)
}

package database.models

import framework.Jsonable
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class CreditNoteLineItem(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  creditNoteId: String,
  description: Option[String],
  rank: Int,
  amount: Long,
  `type`: String,
  invoiceLineItemId: Option[String]
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "rank" -> rank,
    "type" -> `type`,
    "amount" -> amount,
    "description" -> description,
    "invoiceLineItemId" -> invoiceLineItemId,
  )
}


case class RichCreditNoteLineItem(
  base: CreditNoteLineItem,
  pretaxCreditAmounts: Seq[RichCreditNoteLineItemPretaxCreditAmount],
  taxes: Seq[CreditNoteLineItemTax],
) extends Jsonable {
  lazy val totalInclusiveTaxAmount: Long = taxes.filter(_.taxBehavior == "inclusive").map(_.amount).sum
  lazy val totalExclusiveTaxAmount: Long = taxes.filter(_.taxBehavior == "exclusive").map(_.amount).sum
  lazy val totalTaxAmount: Long = taxes.map(_.amount).sum
  lazy val totalDiscountAmount: Long = pretaxCreditAmounts.filter(_.base.`type` == "discount").map(_.base.amount).sum
  lazy val creditGrants: Seq[RichCreditNoteLineItemPretaxCreditAmount] = pretaxCreditAmounts.filter(_.creditBalanceTransaction.exists(_.creditGrant.isDefined))
  // Credit grants are parts of revenue. They will offset AccountsReceivable with PaidCreditGrants or PromotionalCreditGrants (expense)
  lazy val totalPaidCreditGrantedAmount: Long = pretaxCreditAmounts
    .filter(_.creditBalanceTransaction.exists(_.creditGrant.exists(_.category == "paid")))
    .map(_.base.amount)
    .sum
  lazy val totalPromotionalCreditGrantedAmount: Long = pretaxCreditAmounts
    .filter(_.creditBalanceTransaction.exists(_.creditGrant.exists(_.category == "promotional")))
    .map(_.base.amount)
    .sum
  lazy val totalPrincipleAmount: Long = base.amount - totalInclusiveTaxAmount - totalDiscountAmount
  lazy val totalPrincipleAfterCreditGrants: Long = totalPrincipleAmount - totalPaidCreditGrantedAmount - totalPromotionalCreditGrantedAmount
  lazy val totalBeforeAppliedCreditGrants: Long = base.amount + totalExclusiveTaxAmount - totalDiscountAmount
  lazy val total: Long = totalPrincipleAfterCreditGrants + totalTaxAmount
  lazy val subtotal: Long = total - totalExclusiveTaxAmount

  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "totalPrincipleAmount" -> totalPrincipleAmount,
    "totalInclusiveTaxAmount" -> totalInclusiveTaxAmount,
    "totalExclusiveTaxAmount" -> totalExclusiveTaxAmount,
    "totalDiscountAmount" -> totalDiscountAmount,
    "totalPaidCreditGrantedAmount" -> totalPaidCreditGrantedAmount,
    "totalPromotionalCreditGrantedAmount" -> totalPromotionalCreditGrantedAmount,
    "subtotal" -> subtotal,
    "total" -> total,
  )
}

class CreditNoteLineItemTable(tag: Tag) extends Table[CreditNoteLineItem](tag, "credit_note_line_item") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def creditNoteId: Rep[String] = column[String]("credit_note_id")
  def description: Rep[Option[String]] = column[Option[String]]("description")
  def rank: Rep[Int] = column[Int]("rank")
  def amount: Rep[Long] = column[Long]("amount")
  def `type`: Rep[String] = column[String]("type")
  def invoiceLineItemId: Rep[Option[String]] = column[Option[String]]("invoice_line_item_id")

  def * : ProvenShape[CreditNoteLineItem] = (
    stripeAccountId,
    liveMode,
    id,
    creditNoteId,
    description,
    rank,
    amount,
    `type`,
    invoiceLineItemId
  ).<>((CreditNoteLineItem.apply _).tupled, CreditNoteLineItem.unapply)
}

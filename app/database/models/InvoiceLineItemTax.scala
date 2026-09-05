package database.models

import framework.Jsonable
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class InvoiceLineItemTax(
  stripeAccountId: String,
  liveMode: Boolean,
  rank: Int,
  invoiceLineItemId: String,
  amount: Long,
  taxBehaviour: String,
  taxRateId: Option[String]
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "rank" -> rank,
    "amount" -> amount,
    "taxBehaviour" -> taxBehaviour,
    "taxRateId" -> taxRateId,
  )
}


class InvoiceLineItemTaxTable(tag: Tag) extends Table[InvoiceLineItemTax](tag, "invoice_line_item_tax") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def rank: Rep[Int] = column[Int]("rank")
  def invoiceLineItemId: Rep[String] = column[String]("invoice_line_item_id")
  def amount: Rep[Long] = column[Long]("amount")
  def taxBehaviour: Rep[String] = column[String]("tax_behaviour")
  def taxRateId: Rep[Option[String]] = column[Option[String]]("tax_rate_id")

  def * : ProvenShape[InvoiceLineItemTax] = (
    stripeAccountId,
    liveMode,
    rank,
    invoiceLineItemId,
    amount,
    taxBehaviour,
    taxRateId
  ).<>((InvoiceLineItemTax.apply _).tupled, InvoiceLineItemTax.unapply)
}

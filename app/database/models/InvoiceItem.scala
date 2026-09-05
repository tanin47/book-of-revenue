package database.models

import framework.PostgresProfile.api.*
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import services.ExchangeRate
import slick.lifted.{ProvenShape, Rep}

case class InvoiceItem(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  invoiceId: Option[String],
  customerId: String,
  amount: Long,
  currency: String,
  description: Option[String],
  startedAt: Option[Instant],
  endedAt: Option[Instant],
  discountIds: List[String],
  taxRateIds: List[String],
  priceId: Option[String],
  productId: Option[String],
  createdAt: Instant,
  syncedAt: Instant
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "invoiceId" -> invoiceId,
    "customerId" -> customerId,
    "amount" -> amount,
    "currency" -> currency,
    "description" -> description,
    "startedAt" -> startedAt.map(_.toEpochMilli),
    "endedAt" -> endedAt.map(_.toEpochMilli),
    "discountIds" -> discountIds,
    "taxRateIds" -> taxRateIds,
    "createdAt" -> createdAt.toEpochMilli,
  )
}

case class RichInvoiceItem(
  base: InvoiceItem,
  discounts: Seq[RichDiscount],
  taxRates: Seq[TaxRate],
  createdAtExchangeRate: Option[ExchangeRate] = None
) extends Jsonable {
  lazy val totalDiscountAmount: Long = discounts.map(_.computeDiscount(base.amount)).sum
  lazy val totalTaxableAmount: Long = base.amount - totalDiscountAmount
  lazy val totalInclusiveTaxAmount: Long = taxRates.filter(_.inclusive).map(_.computeTax(totalTaxableAmount)).sum
  lazy val totalExclusiveTaxAmount: Long = taxRates.filterNot(_.inclusive).map(_.computeTax(totalTaxableAmount)).sum
  lazy val totalTaxAmount: Long = totalInclusiveTaxAmount + totalExclusiveTaxAmount
  lazy val totalPrincipleAmount: Long = totalTaxableAmount - totalInclusiveTaxAmount
  lazy val subtotal: Long = totalTaxableAmount
  lazy val total: Long = subtotal + totalExclusiveTaxAmount

  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "discounts" -> discounts.map(_.toJson()),
    "taxRates" -> taxRates.map(_.toJson()),
    "totalDiscountAmount" -> totalDiscountAmount,
    "totalPrincipleAmount" -> totalPrincipleAmount,
    "totalInclusiveTaxAmount" -> totalInclusiveTaxAmount,
    "totalExclusiveTaxAmount" -> totalExclusiveTaxAmount,
    "subtotal" -> subtotal,
    "total" -> total,
  )
}

class InvoiceItemTable(tag: Tag) extends Table[InvoiceItem](tag, "invoice_item") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id")
  def invoiceId: Rep[Option[String]] = column[Option[String]]("invoice_id")
  def customerId: Rep[String] = column[String]("customer_id")
  def amount: Rep[Long] = column[Long]("amount")
  def currency: Rep[String] = column[String]("currency")
  def description: Rep[Option[String]] = column[Option[String]]("description")
  def startedAt: Rep[Option[Instant]] = column[Option[Instant]]("started_at")
  def endedAt: Rep[Option[Instant]] = column[Option[Instant]]("ended_at")
  def discountIds: Rep[List[String]] = column[List[String]]("discount_ids")
  def taxRateIds: Rep[List[String]] = column[List[String]]("tax_rate_ids")
  def priceId: Rep[Option[String]] = column[Option[String]]("price_id")
  def productId: Rep[Option[String]] = column[Option[String]]("product_id")
  def createdAt: Rep[Instant] = column[Instant]("created_at")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[InvoiceItem] = (
    stripeAccountId,
    liveMode,
    id,
    invoiceId,
    customerId,
    amount,
    currency,
    description,
    startedAt,
    endedAt,
    discountIds,
    taxRateIds,
    priceId,
    productId,
    createdAt,
    syncedAt
  ).<>((InvoiceItem.apply _).tupled, InvoiceItem.unapply)
}

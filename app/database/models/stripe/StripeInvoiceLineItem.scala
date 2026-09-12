package database.models.stripe

import database.models.stripe.{StripeMeterEventSummary, RichStripePrice, StripeSubscriptionItem}
import framework.PostgresProfile.api.*
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import services.ExchangeRate
import slick.lifted.{ProvenShape, Rep}

case class StripeInvoiceLineItem(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  invoiceId: String,
  description: Option[String],
  amount: Long, // This amount includes pretaxCreditAmounts
  currency: String,
  startedAt: Option[Instant],
  endedAt: Option[Instant],
  rank: Int,
  invoiceItemId: Option[String],
  subscriptionItemId: Option[String],
  priceId: Option[String],
  pricingUnitAmountDecimal: Option[String], // For a usage-based price, if this field is None, then it's the flat fee line item of the usage-based price.
  customerId: String, // This doesn't exist in Stripe API object but we need it here in order to fetch the meter event summaries.
  syncedAt: Instant
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "rank" -> rank,
    "description" -> description,
    "amount" -> amount,
    "currency" -> currency,
    "startedAt" -> startedAt.map(_.toEpochMilli),
    "endedAt" -> endedAt.map(_.toEpochMilli),
    "invoiceItemId" -> invoiceItemId,
    "subscriptionItemId" -> subscriptionItemId,
    "priceId" -> priceId,
    "pricingUnitAmountDecimal" -> pricingUnitAmountDecimal,
  )
}

case class RichStripeInvoiceLineItem(
  base: StripeInvoiceLineItem,
  invoiceItem: Option[RichStripeInvoiceItem],
  subscriptionItem: Option[StripeSubscriptionItem],
  price: Option[RichStripePrice],
  meterEventSummaries: Seq[StripeMeterEventSummary],
  startedAtExchangeRate: ExchangeRate,
  pretaxCreditAmounts: Seq[RichStripeInvoiceLineItemPretaxCreditAmount],
  taxes: Seq[StripeInvoiceLineItemTax],
  creditBalanceTransactionsAppliedOnVoid: Seq[RichStripeCreditBalanceTransaction],
) extends Jsonable {
  lazy val syncedAt: Instant = Seq(
    Some(base.syncedAt),
    invoiceItem.map(_.base.syncedAt),
    subscriptionItem.map(_.syncedAt),
    price.map(_.base.syncedAt),
    meterEventSummaries.map(_.syncedAt).maxOption
  ).flatten.max

  lazy val totalInclusiveTaxAmount: Long = taxes.filter(_.taxBehaviour == "inclusive").map(_.amount).sum
  lazy val totalExclusiveTaxAmount: Long = taxes.filter(_.taxBehaviour == "exclusive").map(_.amount).sum
  lazy val totalTaxAmount: Long = taxes.map(_.amount).sum
  lazy val totalDiscountAmount: Long = pretaxCreditAmounts.filter(_.base.`type` == "discount").map(_.base.amount).sum
  lazy val creditGrants: Seq[RichStripeInvoiceLineItemPretaxCreditAmount] = pretaxCreditAmounts.filter(_.creditBalanceTransaction.exists(_.creditGrant.isDefined))
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
    "invoiceItem" -> invoiceItem.map(_.toJson()),
    "subscriptionItem" -> subscriptionItem.map(_.toJson()),
    "price" -> price.map(_.toJson()),
    "meterEventSummaries" -> meterEventSummaries.map(_.toJson()),
    "startedAtExchangeRate" -> startedAtExchangeRate.toJson(),
    "pretaxCreditAmounts" -> pretaxCreditAmounts.map(_.toJson()),
    "taxes" -> taxes.map(_.toJson()),
    "creditBalanceTransactionsAppliedOnVoid" -> creditBalanceTransactionsAppliedOnVoid.map(_.toJson()),
    "creditGrants" -> creditGrants.map(_.toJson()),
    "totalInclusiveTaxAmount" -> totalInclusiveTaxAmount,
    "totalExclusiveTaxAmount" -> totalExclusiveTaxAmount,
    "totalTaxAmount" -> totalTaxAmount,
    "totalDiscountAmount" -> totalDiscountAmount,
    "totalPaidCreditGrantedAmount" -> totalPaidCreditGrantedAmount,
    "totalPromotionalCreditGrantedAmount" -> totalPromotionalCreditGrantedAmount,
    "totalPrincipleAmount" -> totalPrincipleAmount,
    "totalPrincipleAfterCreditGrants" -> totalPrincipleAfterCreditGrants,
    "totalBeforeAppliedCreditGrants" -> totalBeforeAppliedCreditGrants,
    "subtotal" -> subtotal,
    "total" -> total,
  )
}

class StripeInvoiceLineItemTable(tag: Tag) extends Table[StripeInvoiceLineItem](tag, Some("stripe"), "invoice_line_item") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id")
  def invoiceId: Rep[String] = column[String]("invoice_id")
  def description: Rep[Option[String]] = column[Option[String]]("description")
  def amount: Rep[Long] = column[Long]("amount")
  def currency: Rep[String] = column[String]("currency")
  def startedAt: Rep[Option[Instant]] = column[Option[Instant]]("started_at")
  def endedAt: Rep[Option[Instant]] = column[Option[Instant]]("ended_at")
  def rank: Rep[Int] = column[Int]("rank")
  def invoiceItemId: Rep[Option[String]] = column[Option[String]]("invoice_item_id")
  def subscriptionItemId: Rep[Option[String]] = column[Option[String]]("subscription_item_id")
  def priceId: Rep[Option[String]] = column[Option[String]]("price_id")
  def pricingUnitAmountDecimal: Rep[Option[String]] = column[Option[String]]("pricing_unit_amount_decimal")
  def customerId: Rep[String] = column[String]("customer_id")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[StripeInvoiceLineItem] = (
    stripeAccountId,
    liveMode,
    id,
    invoiceId,
    description,
    amount,
    currency,
    startedAt,
    endedAt,
    rank,
    invoiceItemId,
    subscriptionItemId,
    priceId,
    pricingUnitAmountDecimal,
    customerId,
    syncedAt
  ).<>((StripeInvoiceLineItem.apply _).tupled, StripeInvoiceLineItem.unapply)
}

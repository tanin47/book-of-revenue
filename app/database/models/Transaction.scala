package database.models

import database.models.stripe.*
import framework.{Instant, Jsonable}
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

object Transaction {
  enum Type extends Enum[Type] {
    case
      Invoice,
      StandalonePaymentIntent,
      StandaloneCharge,
      UnbilledInvoiceItem,
      UnbilledUsageSubscriptionItem,
      StandaloneCustomerBalanceTransaction,
      StandaloneCreditBalanceTransaction
  }

  enum Status extends Enum[Status] {
    case Draft, Open, Paid, Uncollectible, Voided, Error, Transacted, Unpaid, Undetermined
  }

  // The source Stripe object a transaction is derived from, carrying the account/mode to attribute the transaction.
  case class Source(
    id: String,
    stripeAccountId: String,
    liveMode: Boolean,
    customerId: Option[String]
  )
}

case class Transaction(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  tpe: Transaction.Type,
  status: Transaction.Status,
  customerId: Option[String],
  title: Option[String],
  settlementTotalValue: Option[Long],
  settlementCurrency: Option[String],
  startedAt: Option[Instant],
  processedAt: Option[Instant],
  syncedAt: Option[Instant],
  batchTimestamp: Instant
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "stripeAccountId" -> stripeAccountId,
    "liveMode" -> liveMode,
    "id" -> id,
    "type" -> tpe.toString,
    "status" -> status.toString,
    "customerId" -> customerId,
    "startedAt" -> startedAt.map(_.toEpochMilli),
    "processedAt" -> processedAt.map(_.toEpochMilli),
    "syncedAt" -> syncedAt.map(_.toEpochMilli),
    "title" -> title,
    "settlementTotalValue" -> settlementTotalValue,
    "settlementCurrency" -> settlementCurrency
  )
}

case class ListableTransaction(
  base: Transaction,
  customer: Option[StripeCustomer],
) extends Jsonable {
  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "customer" -> customer.map(_.toJson()),
  )
}


case class RichTransaction(
  base: Transaction,
  customer: Option[StripeCustomer],
  invoice: Option[RichStripeInvoice],
  charge: Option[RichStripeCharge],
  paymentIntent: Option[RichStripePaymentIntent],
  invoiceItem: Option[RichStripeInvoiceItem],
  subscriptionItem: Option[RichStripeSubscriptionItem],
  customerBalanceTransaction: Option[StripeCustomerBalanceTransaction],
  creditBalanceTransaction: Option[RichStripeCreditBalanceTransaction],
) extends Jsonable {
  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "customer" -> customer.map(_.toJson()),
    "invoice" -> invoice.map(_.toJson()),
    "charge" -> charge.map(_.toJson()),
    "paymentIntent" -> paymentIntent.map(_.toJson()),
    "invoiceItem" -> invoiceItem.map(_.toJson()),
    "subscriptionItem" -> subscriptionItem.map(_.toJson()),
    "customerBalanceTransaction" -> customerBalanceTransaction.map(_.toJson()),
    "creditBalanceTransaction" -> creditBalanceTransaction.map(_.toJson()),
  )
}

class TransactionTable(tag: Tag) extends Table[Transaction](tag, "transaction") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id")
  def tpe: Rep[Transaction.Type] = column[Transaction.Type]("type")
  def status: Rep[Transaction.Status] = column[Transaction.Status]("status")
  def customerId: Rep[Option[String]] = column[Option[String]]("customer_id")
  def title: Rep[Option[String]] = column[Option[String]]("title")
  def settlementTotalValue: Rep[Option[Long]] = column[Option[Long]]("settlement_total_value")
  def settlementCurrency: Rep[Option[String]] = column[Option[String]]("settlement_currency")
  def startedAt: Rep[Option[Instant]] = column[Option[Instant]]("started_at")
  def processedAt: Rep[Option[Instant]] = column[Option[Instant]]("processed_at")
  def syncedAt: Rep[Option[Instant]] = column[Option[Instant]]("synced_at")
  def batchTimestamp: Rep[Instant] = column[Instant]("batch_timestamp")

  def * : ProvenShape[Transaction] = (
    stripeAccountId,
    liveMode,
    id,
    tpe,
    status,
    customerId,
    title,
    settlementTotalValue,
    settlementCurrency,
    startedAt,
    processedAt,
    syncedAt,
    batchTimestamp
  ).<>((Transaction.apply _).tupled, Transaction.unapply)
}

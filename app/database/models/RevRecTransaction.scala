package database.models

import framework.{Instant, Jsonable}
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

object RevRecTransaction {
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

case class RevRecTransaction(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  tpe: RevRecTransaction.Type,
  status: RevRecTransaction.Status,
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

case class ListableRevRecTransaction(
  base: RevRecTransaction,
  customer: Option[Customer],
) extends Jsonable {
  def toJson(): JsObject = base.toJson() ++ Json.obj(
    "customer" -> customer.map(_.toJson()),
  )
}


case class RichRevRecTransaction(
  base: RevRecTransaction,
  customer: Option[Customer],
  invoice: Option[RichInvoice],
  charge: Option[RichCharge],
  paymentIntent: Option[RichPaymentIntent],
  invoiceItem: Option[RichInvoiceItem],
  subscriptionItem: Option[RichSubscriptionItem],
  customerBalanceTransaction: Option[CustomerBalanceTransaction],
  creditBalanceTransaction: Option[RichCreditBalanceTransaction],
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

class RevRecTransactionTable(tag: Tag) extends Table[RevRecTransaction](tag, "rev_rec_transaction") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id")
  def tpe: Rep[RevRecTransaction.Type] = column[RevRecTransaction.Type]("type")
  def status: Rep[RevRecTransaction.Status] = column[RevRecTransaction.Status]("status")
  def customerId: Rep[Option[String]] = column[Option[String]]("customer_id")
  def title: Rep[Option[String]] = column[Option[String]]("title")
  def settlementTotalValue: Rep[Option[Long]] = column[Option[Long]]("settlement_total_value")
  def settlementCurrency: Rep[Option[String]] = column[Option[String]]("settlement_currency")
  def startedAt: Rep[Option[Instant]] = column[Option[Instant]]("started_at")
  def processedAt: Rep[Option[Instant]] = column[Option[Instant]]("processed_at")
  def syncedAt: Rep[Option[Instant]] = column[Option[Instant]]("synced_at")
  def batchTimestamp: Rep[Instant] = column[Instant]("batch_timestamp")

  def * : ProvenShape[RevRecTransaction] = (
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
  ).<>((RevRecTransaction.apply _).tupled, RevRecTransaction.unapply)
}

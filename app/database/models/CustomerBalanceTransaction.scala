package database.models

import framework.PostgresProfile.api.*
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import framework.TransactionDetail.BillingActivity
import framework.TransactionDetail.BillingActivity.{DebitCustomerBalance, CreditCustomerBalance}
import slick.lifted.{ProvenShape, Rep}

case class CustomerBalanceTransaction(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  amount: Long,
  createdAt: Instant,
  currency: String,
  customerId: String,
  description: Option[String],
  endingBalance: Long,
  invoiceId: Option[String],
  creditNoteId: Option[String],
  `type`: String,
  syncedAt: Instant
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "amount" -> amount,
    "createdAt" -> createdAt.toEpochMilli,
    "currency" -> currency,
    "customerId" -> customerId,
    "description" -> description,
    "endingBalance" -> endingBalance,
    "invoiceId" -> invoiceId,
    "creditNoteId" -> creditNoteId,
    "type" -> `type`,
  )

  lazy val billingActivities: Seq[BillingActivity.Value] = Seq(
    if (amount < 0L) {
      CreditCustomerBalance(
        timestamp = createdAt,
        customerBalanceTransactionId = id,
        amount = -amount,
        currency = currency
      )
    } else {
      DebitCustomerBalance(
        timestamp = createdAt,
        customerBalanceTransactionId = id,
        amount = amount,
        currency = currency
      )
    }
  )
}

class CustomerBalanceTransactionTable(tag: Tag) extends Table[CustomerBalanceTransaction](tag, "customer_balance_transaction") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def amount: Rep[Long] = column[Long]("amount")
  def createdAt: Rep[Instant] = column[Instant]("created_at")
  def currency: Rep[String] = column[String]("currency")
  def customerId: Rep[String] = column[String]("customer_id")
  def description: Rep[Option[String]] = column[Option[String]]("description")
  def endingBalance: Rep[Long] = column[Long]("ending_balance")
  def invoiceId: Rep[Option[String]] = column[Option[String]]("invoice_id")
  def creditNoteId: Rep[Option[String]] = column[Option[String]]("credit_note_id")
  def `type`: Rep[String] = column[String]("type")
  def syncedAt: Rep[Instant] = column[Instant]("synced_at")

  def * : ProvenShape[CustomerBalanceTransaction] = (
    stripeAccountId,
    liveMode,
    id,
    amount,
    createdAt,
    currency,
    customerId,
    description,
    endingBalance,
    invoiceId,
    creditNoteId,
    `type`,
    syncedAt
  ).<>((CustomerBalanceTransaction.apply _).tupled, CustomerBalanceTransaction.unapply)
}

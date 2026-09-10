package database.models

import framework.PostgresProfile.api.*
import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}
import slick.collection.heterogeneous.HNil
import slick.jdbc.SQLActionBuilder
import slick.lifted.{ProvenShape, Rep}

import scala.language.postfixOps

object JournalEntry {
  enum AccountCategory extends Enum[AccountCategory] {
    case Asset, ContraAsset, ContractLiability, StatutoryLiability, NonFinancialLiability, Revenue, ContraRevenue, Expense, Gain

    def isCredit(): Boolean = {
      Set(ContractLiability, StatutoryLiability, NonFinancialLiability, Revenue, Gain).contains(this)
    }
  }

  def getCategorySqlCond(accountFieldName: String): SQLActionBuilder = {
    val categories = Account.values.groupBy(_.getAccountCategory()).toList.map { case (category, accounts) =>
      sql"WHEN #${accountFieldName} = ANY(${accounts.map(_.toString).toList}) THEN ${category.toString} "
    }

    makeSql(Seq(
      Seq(sql"CASE"),
      categories,
      Seq(sql"END")
    ).flatten *)
  }

  enum Account extends Enum[Account] {
    case
      AccountsReceivable,
      BadDebt,
      Cash,
      CreditNotes,
      CustomerBalance,
      CustomerBalanceAdjustment,
      DeferredRevenue,
      Disputes,
      Fees,
      Loss,
      OutOfBandAssets,
      PaidCreditGrants,
      PaidCreditGrantContraAsset,
      PromotionalCreditGrants,
      PromotionalCreditGrantExpense,
      Recoverables,
      Refunds,
      Revenue,
      TaxLiability,
      UnbilledAccountsReceivable,
      UnbilledDeferredRevenue,
      UndepositedFunds,
      Underpayment,
      Voids

    def getAccountCategory(): AccountCategory = {
      this match {
        case AccountsReceivable => AccountCategory.Asset
        case BadDebt => AccountCategory.Expense
        case Cash => AccountCategory.Asset
        case CreditNotes => AccountCategory.ContraRevenue
        case CustomerBalance => AccountCategory.ContractLiability
        case CustomerBalanceAdjustment => AccountCategory.Expense
        case DeferredRevenue => AccountCategory.ContractLiability
        case Disputes => AccountCategory.ContraRevenue
        case Fees => AccountCategory.Expense
        case Loss => AccountCategory.Expense
        case OutOfBandAssets => AccountCategory.Asset
        case PaidCreditGrants => AccountCategory.ContractLiability
        case PaidCreditGrantContraAsset => AccountCategory.ContraAsset
        case PromotionalCreditGrants => AccountCategory.ContractLiability
        case PromotionalCreditGrantExpense => AccountCategory.Expense
        case Recoverables => AccountCategory.Gain
        case Refunds => AccountCategory.ContraRevenue
        case Revenue => AccountCategory.Revenue
        case TaxLiability => AccountCategory.StatutoryLiability
        case UnbilledAccountsReceivable => AccountCategory.Asset
        case UnbilledDeferredRevenue => AccountCategory.NonFinancialLiability
        case UndepositedFunds => AccountCategory.Asset
        case Underpayment => AccountCategory.Expense
        case Voids => AccountCategory.ContraRevenue
      }
    }

    def isCredit(): Boolean = getAccountCategory().isCredit()
  }

  enum Event extends Enum[Event] {
    case
      AdjustCustomerBalanceManually,
      CreateCharge,
      CreateCreditGrant,
      CreateUnbilledInvoiceItem,
      DisputeCharge,
      ExpireCreditGrant,
      FailRefund,
      FinalizeInvoice,
      IssueCreditNote,
      MarkPaid,
      MarkUncollectible,
      PayInvoice,
      PayFee,
      RecognizeRevenue,
      RefundCharge,
      VoidCreditGrant,
      VoidCreditNote,
      VoidInvoice,
      WinDispute
  }
}

case class JournalEntry(
  stripeAccountId: String,
  liveMode: Boolean,
  accountingPeriod: Instant,
  attributionPeriod: Option[Instant],
  debit: JournalEntry.Account,
  credit: JournalEntry.Account,
  settlementAmount: Long,
  settlementCurrency: String,
  presentmentAmount: Long,
  presentmentCurrency: String,
  occurredAt: Instant,
  event: JournalEntry.Event,
  reversedEvent: Option[JournalEntry.Event],
  principleAccount: JournalEntry.Account,
  transactionId: String,
  transactionType: Transaction.Type,
  stripeCustomerId: Option[String],
  stripeInvoiceId: Option[String],
  stripeInvoiceLineItemId: Option[String],
  stripeInvoiceItemId: Option[String],
  stripeChargeId: Option[String],
  stripeBalanceTransactionId: Option[String],
  stripeDisputeId: Option[String],
  stripeRefundId: Option[String],
  stripeCustomerBalanceTransactionId: Option[String],
  stripePaymentIntentId: Option[String],
  stripePaymentRecordId: Option[String],
  stripeSubscriptionId: Option[String],
  stripeSubscriptionItemId: Option[String],
  stripeCreditBalanceTransactionId: Option[String],
  stripeCreditNoteId: Option[String],
  stripeCreditNoteLineItemId: Option[String],
  stripeProductId: Option[String],
  stripePriceId: Option[String],
  createdAt: Instant
) extends Jsonable {
  def swap(): JournalEntry = copy(
    debit = credit,
    credit = debit,
    settlementAmount = -settlementAmount,
    presentmentAmount = -presentmentAmount
  )

  def toJson(): JsObject = Json.obj(
    "accountingPeriod" -> accountingPeriod,
    "debit" -> debit.toString,
    "credit" -> credit.toString,
    "amount" -> settlementAmount,
    "currency" -> settlementCurrency,
    "presentmentAmount" -> presentmentAmount,
    "presentmentCurrency" -> presentmentCurrency,
    "occurredAt" -> occurredAt,
    "transactionId" -> transactionId,
    "transactionType" -> transactionType.toString,
    "customerId" -> stripeCustomerId,
    "event" -> event.toString,
    "invoiceId" -> stripeInvoiceId,
    "invoiceLineItemId" -> stripeInvoiceLineItemId,
    "invoiceItemId" -> stripeInvoiceItemId,
    "chargeId" -> stripeChargeId,
    "balanceTransactionId" -> stripeBalanceTransactionId,
    "disputeId" -> stripeDisputeId,
    "refundId" -> stripeRefundId,
    "customerBalanceTransactionId" -> stripeCustomerBalanceTransactionId,
    "paymentIntentId" -> stripePaymentIntentId,
    "paymentRecordId" -> stripePaymentRecordId,
    "subscriptionId" -> stripeSubscriptionId,
    "subscriptionItemId" -> stripeSubscriptionItemId,
    "creditBalanceTransactionId" -> stripeCreditBalanceTransactionId,
    "createdAt" -> createdAt
  )
}

class JournalEntryTable(tag: Tag) extends Table[JournalEntry](tag, "journal_entry") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def accountingPeriod: Rep[Instant] = column[Instant]("accounting_period")
  def attributionPeriod: Rep[Option[Instant]] = column[Option[Instant]]("attribution_period")
  def debit: Rep[JournalEntry.Account] = column[JournalEntry.Account]("debit")
  def credit: Rep[JournalEntry.Account] = column[JournalEntry.Account]("credit")
  def settlementAmount: Rep[Long] = column[Long]("settlement_amount")
  def settlementCurrency: Rep[String] = column[String]("settlement_currency")
  def presentmentAmount: Rep[Long] = column[Long]("presentment_amount")
  def presentmentCurrency: Rep[String] = column[String]("presentment_currency")
  def occurredAt: Rep[Instant] = column[Instant]("occurred_at")
  def event: Rep[JournalEntry.Event] = column[JournalEntry.Event]("event")
  def reversedEvent: Rep[Option[JournalEntry.Event]] = column[Option[JournalEntry.Event]]("reversed_event")
  def principleAccount: Rep[JournalEntry.Account] = column[JournalEntry.Account]("principle_account")
  def transactionId: Rep[String] = column[String]("transaction_id")
  def transactionType: Rep[Transaction.Type] = column[Transaction.Type]("transaction_type")
  def customerId: Rep[Option[String]] = column[Option[String]]("customer_id")
  def invoiceId: Rep[Option[String]] = column[Option[String]]("invoice_id")
  def invoiceLineItemId: Rep[Option[String]] = column[Option[String]]("invoice_line_item_id")
  def invoiceItemId: Rep[Option[String]] = column[Option[String]]("invoice_item_id")
  def chargeId: Rep[Option[String]] = column[Option[String]]("charge_id")
  def balanceTransactionId: Rep[Option[String]] = column[Option[String]]("balance_transaction_id")
  def disputeId: Rep[Option[String]] = column[Option[String]]("dispute_id")
  def refundId: Rep[Option[String]] = column[Option[String]]("refund_id")
  def customerBalanceTransactionId: Rep[Option[String]] = column[Option[String]]("customer_balance_transaction_id")
  def paymentIntentId: Rep[Option[String]] = column[Option[String]]("payment_intent_id")
  def paymentRecordId: Rep[Option[String]] = column[Option[String]]("payment_record_id")
  def subscriptionId: Rep[Option[String]] = column[Option[String]]("subscription_id")
  def subscriptionItemId: Rep[Option[String]] = column[Option[String]]("subscription_item_id")
  def creditBalanceTransactionId: Rep[Option[String]] = column[Option[String]]("credit_balance_transaction_id")
  def creditNoteId: Rep[Option[String]] = column[Option[String]]("credit_note_id")
  def creditNoteLineItemId: Rep[Option[String]] = column[Option[String]]("credit_note_line_item_id")
  def productId: Rep[Option[String]] = column[Option[String]]("product_id")
  def priceId: Rep[Option[String]] = column[Option[String]]("price_id")
  def createdAt: Rep[Instant] = column[Instant]("created_at")

  def * : ProvenShape[JournalEntry] = (
    stripeAccountId ::
    liveMode ::
    accountingPeriod ::
    attributionPeriod ::
    debit ::
    credit ::
    settlementAmount ::
    settlementCurrency ::
    presentmentAmount ::
    presentmentCurrency ::
    occurredAt ::
    event ::
    reversedEvent ::
    principleAccount ::
    transactionId ::
    transactionType ::
    customerId ::
    invoiceId ::
    invoiceLineItemId ::
    invoiceItemId ::
    chargeId ::
    balanceTransactionId ::
    disputeId ::
    refundId ::
    customerBalanceTransactionId ::
    paymentIntentId ::
    paymentRecordId ::
    subscriptionId ::
    subscriptionItemId ::
    creditBalanceTransactionId ::
    creditNoteId ::
    creditNoteLineItemId ::
    productId ::
    priceId ::
    createdAt ::
      HNil
  ).mapTo[JournalEntry]
}

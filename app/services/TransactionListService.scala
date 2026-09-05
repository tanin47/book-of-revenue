package services

import database.models.JournalEntry
import database.models.JournalEntry.AccountCategory
import database.services.JournalEntryService.{ColumnType, SortDirection, getValue}
import framework.{Instant, Jsonable, PlayConfig}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.*
import slick.jdbc.{GetResult, JdbcProfile, SQLActionBuilder}

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

object TransactionListService {
  enum Column extends Enum[Column] {
    case
      RevRecTransactionType,
      RevRecTransactionId,
      RevRecTransactionTitle,
      SettlementTotalTransactionValue,
      SettlementCurrency,
      TransactionStartedAt,
      RevenueAmount,
      ContraRevenueAmount,
      ContractLiabilityAmount,
      StatutoryLiabilityAmount,
      NonFinancialLiabilityAmount,
      AssetAmount,
      ContraAssetAmount,
      ExpenseAmount,
      GainAmount

  }

  case class Sort(column: Column, direction: SortDirection)
  case class Params(
    customerId: Option[Option[String]],
    keyword: String,
    columns: Seq[Column],
    sorts: Seq[TransactionListService.Sort],
  )

  case class ResultColumn(
    id: Column,
    tpe: ColumnType,
    maxCharacterLength: Int
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "id" -> id.name,
      "type" -> tpe.toString,
      "maxCharacterLength" -> maxCharacterLength
    )
  }
  case class Result(
    columns: Seq[ResultColumn],
    rows: Seq[Seq[Option[Any]]]
  )

  def makeGetResult(resultColumns: Seq[ResultColumn]): GetResult[Seq[Option[Any]]] = {
    GetResult[Seq[Option[Any]]] { r =>
      resultColumns.map { column =>
        val value = getValue(column.tpe, r)

        if (r.wasNull()) {
          None
        } else {
          value
        }
      }
    }
  }
}

class TransactionListService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import TransactionListService.*
  import framework.PostgresProfile.api.*

  private[this] def convertToSqlColumnName(column: Column): SQLActionBuilder = {
    column match {
      case Column.RevRecTransactionType => sql"rev_rec_transaction_type"
      case Column.RevRecTransactionId => sql"rev_rec_transaction_id"
      case Column.RevRecTransactionTitle => sql"rev_rec_transaction_title"
      case Column.SettlementTotalTransactionValue => sql"settlement_total_value"
      case Column.SettlementCurrency => sql"settlement_currency"
      case Column.TransactionStartedAt => sql"transaction_started_at"
      case Column.RevenueAmount => sql"Revenue_amount"
      case Column.ContraRevenueAmount => sql"ContraRevenue_amount"
      case Column.ContractLiabilityAmount => sql"ContractLiability_amount"
      case Column.StatutoryLiabilityAmount => sql"StatutoryLiability_amount"
      case Column.NonFinancialLiabilityAmount => sql"NonFinancialLiability_amount"
      case Column.AssetAmount => sql"Asset_amount"
      case Column.ContraAssetAmount => sql"ContraAsset_amount"
      case Column.ExpenseAmount => sql"Expense_amount"
      case Column.GainAmount => sql"Gain_amount"
    }
  }

  private[this] def makeOrderByClause(sorts: Seq[Sort]): SQLActionBuilder = {
    if (sorts.isEmpty) {
      return sql"ORDER BY transaction_started_at DESC, rev_rec_transaction_title ASC"
    }

    val sortClauses = sorts.map { sort =>
      makeSql(convertToSqlColumnName(sort.column), sql" #${sort.direction.toString.toUpperCase}")
    }

    makeSql(sql"ORDER BY ", joinSqls(sortClauses, sql", "))
  }

  private[this] def makeSelectedColumns(params: Params): SQLActionBuilder = {
    joinSqls(
      params.columns.map(convertToSqlColumnName),
      sql", "
    )
  }

  private def makeEntriesSql(stripeAccountId: String, liveMode: Boolean, params: Params): SQLActionBuilder = {
    val revenueAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.Revenue).toList
    val contraRevenueAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.ContraRevenue).toList
    val contractLiabilityAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.ContractLiability).toList
    val statutoryLiabilityAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.StatutoryLiability).toList
    val nonFinancialLiabilityAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.NonFinancialLiability).toList
    val assetAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.Asset).toList
    val contraAssetAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.ContraAsset).toList
    val expenseAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.Expense).toList
    val gainAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.Gain).toList

    val keyword = params.keyword.trim()
    val whereClause = if (keyword.isEmpty) {
      sql""
    } else {
      val modifiedKeyword = s"%$keyword%"
      val condition = joinSqls(
        Seq(
          sql"con.id ilike $modifiedKeyword",
          sql"inv.number ilike $modifiedKeyword",
          sql"ii.description ilike $modifiedKeyword",
          sql"pi.description ilike $modifiedKeyword",
          sql"ch.description ilike $modifiedKeyword",
        ),
        sql"OR"
      )
      makeSql(sql"AND (", condition, sql")")
    }
    val customerIdClause = params.customerId match {
      case None => sql""
      case Some(None) => sql"AND con.customer_id IS NULL"
      case Some(Some(id)) => sql"AND con.customer_id = $id"
    }

    makeSql(
      sql"""
        SELECT
          con.id AS rev_rec_transaction_id,
          MAX(con.title) AS rev_rec_transaction_title,
          MAX(con.settlement_total_value) AS settlement_total_value,
          MAX(con.settlement_currency) AS settlement_currency,
          MAX(con.started_at) AS transaction_started_at,
          MAX(
            CASE con.type
            WHEN 'Invoice' THEN 'Invoice'
            WHEN 'StandaloneCharge' THEN 'Charge'
            WHEN 'StandalonePaymentIntent' THEN 'Payment'
            WHEN 'UnbilledInvoiceItem' THEN 'UnbilledInvoiceItem'
            WHEN 'UnbilledUsageSubscriptionItem' THEN 'UnbilledUsage'
            WHEN 'StandaloneCustomerBalanceTransaction' THEN 'CustomerBalanceTransaction'
            WHEN 'StandaloneCreditBalanceTransaction' THEN 'CreditBalanceTransaction'
            ELSE 'Unknown'
            END
          ) AS rev_rec_transaction_type,
          SUM(
            (CASE WHEN debit = ANY(${revenueAccounts.map(_.name)}) THEN -settlement_amount ELSE 0 END)
            + (CASE WHEN credit = ANY(${revenueAccounts.map(_.name)}) THEN settlement_amount ELSE 0 END)
          ) AS Revenue_amount,
          SUM(
            (CASE WHEN debit = ANY(${contraRevenueAccounts.map(_.name)}) THEN settlement_amount ELSE 0 END)
            + (CASE WHEN credit = ANY(${contraRevenueAccounts.map(_.name)}) THEN -settlement_amount ELSE 0 END)
          ) AS ContraRevenue_amount,
          SUM(
            (CASE WHEN debit = ANY(${contractLiabilityAccounts.map(_.name)}) THEN -settlement_amount ELSE 0 END)
            + (CASE WHEN credit = ANY(${contractLiabilityAccounts.map(_.name)}) THEN settlement_amount ELSE 0 END)
          ) AS ContractLiability_amount,
          SUM(
            (CASE WHEN debit = ANY(${statutoryLiabilityAccounts.map(_.name)}) THEN -settlement_amount ELSE 0 END)
            + (CASE WHEN credit = ANY(${statutoryLiabilityAccounts.map(_.name)}) THEN settlement_amount ELSE 0 END)
          ) AS StatutoryLiability_amount,
          SUM(
            (CASE WHEN debit = ANY(${nonFinancialLiabilityAccounts.map(_.name)}) THEN -settlement_amount ELSE 0 END)
            + (CASE WHEN credit = ANY(${nonFinancialLiabilityAccounts.map(_.name)}) THEN settlement_amount ELSE 0 END)
          ) AS NonFinancialLiability_amount,
          SUM(
            (CASE WHEN debit = ANY(${assetAccounts.map(_.name)}) THEN settlement_amount ELSE 0 END)
            + (CASE WHEN credit = ANY(${assetAccounts.map(_.name)}) THEN -settlement_amount ELSE 0 END)
          ) AS Asset_amount,
          SUM(
            (CASE WHEN debit = ANY(${contraAssetAccounts.map(_.name)}) THEN settlement_amount ELSE 0 END)
            + (CASE WHEN credit = ANY(${contraAssetAccounts.map(_.name)}) THEN -settlement_amount ELSE 0 END)
          ) AS ContraAsset_amount,
          SUM(
            (CASE WHEN debit = ANY(${expenseAccounts.map(_.name)}) THEN settlement_amount ELSE 0 END)
            + (CASE WHEN credit = ANY(${expenseAccounts.map(_.name)}) THEN -settlement_amount ELSE 0 END)
          ) AS Expense_amount,
          SUM(
            (CASE WHEN debit = ANY(${gainAccounts.map(_.name)}) THEN -settlement_amount ELSE 0 END)
            + (CASE WHEN credit = ANY(${gainAccounts.map(_.name)}) THEN settlement_amount ELSE 0 END)
          ) AS Gain_amount
        FROM rev_rec_transaction con
        LEFT JOIN journal_entry j
        ON j.rev_rec_transaction_id = con.id AND j.accounting_period <= ${Instant.now()}
        LEFT JOIN invoice inv
        ON con.id = inv.id AND con.type = 'Invoice'
        LEFT JOIN charge ch
        ON con.id = ch.id AND con.type = 'StandaloneCharge'
        LEFT JOIN payment_intent pi
        ON con.id = pi.id AND con.type = 'StandalonePaymentIntent'
        LEFT JOIN invoice_item ii
        ON con.id = ii.id AND con.type = 'UnbilledInvoiceItem'
        LEFT JOIN subscription_item si
        ON con.id = si.id AND con.type = 'UnbilledUsageSubscriptionItem'
        LEFT JOIN customer_balance_transaction cbtxn
        ON con.id = cbtxn.id AND con.type = 'StandaloneCustomerBalanceTransaction'
        LEFT JOIN credit_balance_transaction crtxn
        ON con.id = crtxn.id AND con.type = 'StandaloneCreditBalanceTransaction'
        WHERE con.stripe_account_id = $stripeAccountId AND con.live_mode = $liveMode
      """,
      customerIdClause,
      whereClause,
      sql"""
        GROUP BY con.id
      """
    )
  }

  private def makeBaseSql(stripeAccountId: String, liveMode: Boolean, params: Params): SQLActionBuilder = {
    makeSql(
      sql"""
        WITH entries AS (
      """,
      makeEntriesSql(stripeAccountId, liveMode, params),
      sql"""
        ),

        groups AS (
          SELECT
      """,
      makeSelectedColumns(params),
      sql"FROM entries",
      sql")"
    )
  }

  private[this] def getResultColumns(params: Params): Seq[ResultColumn] = {
    params.columns.map { column =>
      ResultColumn(
        id = column,
        tpe = column match {
          case Column.RevRecTransactionId => ColumnType.String
          case Column.TransactionStartedAt => ColumnType.Date
          case Column.SettlementTotalTransactionValue => ColumnType.Amount
          case Column.SettlementCurrency => ColumnType.String
          case Column.RevRecTransactionTitle => ColumnType.String
          case Column.RevRecTransactionType => ColumnType.String
          case Column.RevenueAmount => ColumnType.Amount
          case Column.ContraRevenueAmount => ColumnType.Amount
          case Column.ContractLiabilityAmount => ColumnType.Amount
          case Column.StatutoryLiabilityAmount => ColumnType.Amount
          case Column.NonFinancialLiabilityAmount => ColumnType.Amount
          case Column.AssetAmount => ColumnType.Amount
          case Column.ContraAssetAmount => ColumnType.Amount
          case Column.ExpenseAmount => ColumnType.Amount
          case Column.GainAmount => ColumnType.Amount
        },
        maxCharacterLength = 0
      )
    }
  }

  def count(stripeAccountId: String, liveMode: Boolean, params: Params): Future[Long] = {
    db
      .run {
        makeSql(
          makeBaseSql(stripeAccountId, liveMode, params),
          sql"""
            SELECT COUNT(*) FROM groups
          """
        ).as[Long]

      }
      .map { result =>
        result.headOption.getOrElse(0L)
      }
  }

  def get(stripeAccountId: String, liveMode: Boolean, params: Params, offset: Int, limit: Int): Future[Result] = {
    val resultColumns = getResultColumns(params)
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns)

    db
      .run {
        makeSql(
          makeBaseSql(stripeAccountId, liveMode, params),
          sql"SELECT * FROM groups",
          makeOrderByClause(params.sorts),
          sql"LIMIT $limit OFFSET $offset"
        ).as[Seq[Option[Any]]]
      }
      .map { rows =>
        Result(resultColumns, rows.toList)
      }
  }
}

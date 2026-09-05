package services

import database.models.JournalEntry
import database.models.JournalEntry.{AccountCategory, getCategorySqlCond}
import database.services.JournalEntryService.{ColumnType, SortDirection, getValue}
import framework.Helpers.{escapeCsv, formatCsvValue}
import framework.{Instant, Jsonable, PlayConfig}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import slick.jdbc.{GetResult, JdbcProfile, SQLActionBuilder}

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.charset.StandardCharsets
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

object IncomeStatementService {
  enum Column extends Enum[Column] {
    case
    Account,
    AccountingPeriod,
    NetIncome,
    AttributionPeriod,
    Category,
    RevRecTransactionId,
    RevRecTransactionTitle,
    CustomerEmail,
    CustomerId,
    CustomerName,
    Event,
    InvoiceId,
    InvoiceLineItemDescription,
    InvoiceLineItemEndedAt,
    InvoiceLineItemId,
    InvoiceLineItemStartedAt,
    InvoiceNumber,
    OccurredAt,
    ProductId,
    ProductName
  }
  case class Sort(column: Column, direction: SortDirection)
  enum GroupBy extends Enum[GroupBy] {
    case Summary, Product, Customer, Transaction, LineItem
  }
  case class Params(
    periodStart: Option[Instant],
    periodEnd: Option[Instant],
    groupBy: Option[GroupBy],
    currency: String,
    productId: Option[String],
    customerId: Option[String],
    transactionId: Option[String],
    accounts: Seq[String],
    columns: Seq[Column],
    sorts: Seq[Sort]
  )

  case class ResultColumn(
    id: Column,
    tpe: ColumnType,
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "id" -> id.toString,
      "type" -> tpe.toString,
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

@Singleton
class IncomeStatementService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import IncomeStatementService.*
  import framework.PostgresProfile.api.*


  private[this] def makeOrderByClause(sorts: Seq[Sort]): SQLActionBuilder = {
    if (sorts.isEmpty) {
      return sql"ORDER BY accounting_period ASC, net_settlement_income DESC, account ASC"
    }

    val sortClauses = sorts.map { sort =>
      val name = sort.column match {
        case Column.AccountingPeriod => "accounting_period"
        case Column.AttributionPeriod => "attribution_period"
        case Column.Account => "account"
        case Column.Category => "category"
        case Column.NetIncome => "net_settlement_income"
        case Column.OccurredAt => "occurred_at"
        case Column.Event => "event"
        case Column.ProductId => "product_id"
        case Column.ProductName => "product_name"
        case Column.CustomerId => "customer_id"
        case Column.CustomerName => "customer_name"
        case Column.CustomerEmail => "customer_email"
        case Column.RevRecTransactionId => "rev_rec_transaction_id"
        case Column.RevRecTransactionTitle => "rev_rec_transaction_title"
        case Column.InvoiceId => "invoice_id"
        case Column.InvoiceNumber => "invoice_number"
        case Column.InvoiceLineItemId => "invoice_line_item_id"
        case Column.InvoiceLineItemDescription => "invoice_line_item_description"
        case Column.InvoiceLineItemStartedAt => "invoice_line_item_started_at"
        case Column.InvoiceLineItemEndedAt => "invoice_line_item_ended_at"
      }

      sql"#${name} #${sort.direction.toString.toUpperCase}"
    }

    makeSql(sql"ORDER BY ", joinSqls(sortClauses, sql", "))
  }

  private[this] def makeSelectedColumns(params: Params): SQLActionBuilder = {
    joinSqls(
      if (params.groupBy.isEmpty) {
        params.columns.map {
          case Column.AccountingPeriod => sql"accounting_period"
          case Column.AttributionPeriod => sql"attribution_period"
          case Column.Account => sql"account"
          case Column.Category => sql"category"
          case Column.NetIncome => sql"net_settlement_income"
          case Column.OccurredAt => sql"occurred_at"
          case Column.Event => sql"event"
          case Column.ProductId => sql"product_id"
          case Column.ProductName => sql"product_name"
          case Column.CustomerId => sql"product_id"
          case Column.CustomerName => sql"customer_name"
          case Column.CustomerEmail => sql"customer_email"
          case Column.RevRecTransactionId => sql"rev_rec_transaction_id"
          case Column.RevRecTransactionTitle => sql"rev_rec_transaction_title"
          case Column.InvoiceId => sql"invoice_id"
          case Column.InvoiceNumber => sql"invoice_number"
          case Column.InvoiceLineItemDescription => sql"invoice_line_item_description"
          case Column.InvoiceLineItemId => sql"invoice_line_item_id"
          case Column.InvoiceLineItemStartedAt => sql"invoice_line_item_started_at"
          case Column.InvoiceLineItemEndedAt => sql"invoice_line_item_ended_at"
        }
      } else {
        params.columns.map {
          case Column.AccountingPeriod => sql"accounting_period"
          case Column.Account => sql"account"
          case Column.Category => makeSql(getCategorySqlCond("account"), sql" AS category")
          case Column.NetIncome => sql"SUM(net_settlement_income) AS net_settlement_income"
          case Column.ProductId => sql"MAX(product_id) AS product_id"
          case Column.ProductName => sql"MAX(product_name) AS product_name"
          case Column.CustomerId => sql"MAX(customer_id) AS product_id"
          case Column.CustomerName => sql"MAX(customer_name) AS customer_name"
          case Column.CustomerEmail => sql"MAX(customer_email) AS customer_email"
          case Column.RevRecTransactionId => sql"MAX(rev_rec_transaction_id) AS rev_rec_transaction_id"
          case Column.RevRecTransactionTitle => sql"MAX(rev_rec_transaction_title) AS rev_rec_transaction_title"
          case Column.InvoiceId => sql"MAX(invoice_id) AS invoice_id"
          case Column.InvoiceNumber => sql"MAX(invoice_number) AS invoice_number"
          case Column.InvoiceLineItemDescription => sql"MAX(invoice_line_item_description) AS invoice_line_item_description"
          case Column.InvoiceLineItemId => sql"MAX(invoice_line_item_id) AS invoice_line_item_id"
          case Column.InvoiceLineItemStartedAt => sql"MIN(invoice_line_item_started_at) AS invoice_line_item_started_at"
          case Column.InvoiceLineItemEndedAt => sql"MAX(invoice_line_item_ended_at) AS invoice_line_item_ended_at"
          case other => throw new Exception(s"Invalid column in the grouping mode: $other")
        }
      },
      sql", "
    )
  }

  private[this] def getResultColumns(params: Params): Seq[ResultColumn] = {
    params.columns.map { column =>
      ResultColumn(
        id = column,
        tpe = column match {
          case Column.AccountingPeriod => ColumnType.Period
          case Column.AttributionPeriod => ColumnType.Period
          case Column.Account => ColumnType.String
          case Column.Category => ColumnType.String
          case Column.NetIncome => ColumnType.Amount
          case Column.OccurredAt => ColumnType.Timestamp
          case Column.Event => ColumnType.String
          case Column.ProductId => ColumnType.String
          case Column.ProductName => ColumnType.String
          case Column.CustomerId => ColumnType.String
          case Column.CustomerName => ColumnType.String
          case Column.CustomerEmail => ColumnType.String
          case Column.RevRecTransactionId => ColumnType.String
          case Column.RevRecTransactionTitle => ColumnType.String
          case Column.InvoiceId => ColumnType.String
          case Column.InvoiceNumber => ColumnType.String
          case Column.InvoiceLineItemDescription => ColumnType.String
          case Column.InvoiceLineItemId => ColumnType.String
          case Column.InvoiceLineItemStartedAt => ColumnType.Timestamp
          case Column.InvoiceLineItemEndedAt => ColumnType.Timestamp
        },
      )
    }
  }

  private[this] def makeGroupByClause(params: Params): SQLActionBuilder = {
    params.groupBy
      .map {
        case GroupBy.Product => sql", product_id"
        case GroupBy.Customer => sql", customer_id"
        case GroupBy.Transaction => sql", rev_rec_transaction_id"
        case GroupBy.LineItem => sql", rev_rec_transaction_id, invoice_line_item_id"
        case GroupBy.Summary => sql""
      }
      .map { extraGroupKey =>
        makeSql(sql"GROUP BY accounting_period, category, account", extraGroupKey)
      }
      .getOrElse(sql"")
  }

  private[this] def makeBaseWithSql(stripeAccountId: String, liveMode: Boolean, params: Params): SQLActionBuilder = {
    val whereClause = joinSqls(
      Seq(
        Some(sql"stripe_account_id = $stripeAccountId"),
        Some(sql"live_mode = $liveMode"),
        Some(sql"settlement_currency = ${params.currency}"),
        params.periodStart.map { p => sql"accounting_period >= ${p}" },
        params.periodEnd.map { p => sql"accounting_period <= ${p}" },
        params.productId.map { c => sql"product_id = $c" },
        params.customerId.map { c => sql"customer_id = $c" },
        params.transactionId.map { c => sql"rev_rec_transaction_id = $c" },
        if (params.accounts.nonEmpty) {
          Some(sql"(debit = ANY(${params.accounts}) OR credit = ANY(${params.accounts}))")
        } else {
          None
        }
      ).flatten,
      sql" AND "
    )

    val revenueAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.Revenue).toList
    val contraRevenueAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.ContraRevenue).toList
    val expenseAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.Expense).toList
    val gainAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.Gain).toList

    makeSql(
      sql"""
        WITH credit_entries AS (
          SELECT
            *,
            credit as account,
            CASE
              WHEN credit = ANY(${(revenueAccounts ++ gainAccounts).map(_.name)}) THEN settlement_amount
              WHEN credit = ANY(${(contraRevenueAccounts ++ expenseAccounts).map(_.name)}) THEN settlement_amount
              ELSE 0
            END AS net_settlement_income
          FROM journal_entry
          WHERE
      """,
      whereClause,
      sql"""
        ),
        debit_entries AS (
          SELECT
            *,
            debit as account,
            CASE
              WHEN debit = ANY(${(revenueAccounts ++ gainAccounts).map(_.name)}) THEN -settlement_amount
              WHEN debit = ANY(${(contraRevenueAccounts ++ expenseAccounts).map(_.name)}) THEN -settlement_amount
              ELSE 0
            END AS net_settlement_income
          FROM journal_entry
          WHERE
      """,
      whereClause,
      // TODO: we should move the joins to after grouping
      sql"""
        ),
        raw_entries AS (
          SELECT * FROM credit_entries UNION ALL SELECT * FROM debit_entries
        ),
        entries AS (
          SELECT
            j.*,
            cus.name AS customer_name,
            cus.email AS customer_email,
            inv.number AS invoice_number,
            il.description AS invoice_line_item_description,
            il.started_at AS invoice_line_item_started_at,
            il.ended_at AS invoice_line_item_ended_at,
            pr.name AS product_name,
            co.title AS rev_rec_transaction_title
          FROM raw_entries j
          LEFT JOIN customer cus ON cus.id = j.customer_id
          LEFT JOIN invoice inv ON inv.id = j.invoice_id
          LEFT JOIN invoice_line_item il ON il.id = j.invoice_line_item_id
          LEFT JOIN product pr ON pr.id = j.product_id
          LEFT JOIN rev_rec_transaction co ON co.id = j.rev_rec_transaction_id
          WHERE net_settlement_income != 0
        ),

        groups AS (
          SELECT
      """,
      makeSelectedColumns(params),
      sql"""
          FROM entries
      """,
      makeGroupByClause(params),
      sql"""
          HAVING SUM(net_settlement_income) != 0
        )
      """
    )
  }

  def count(stripeAccountId: String, liveMode: Boolean, params: Params): Future[Long] = {
    db
      .run {
        makeSql(
          makeBaseWithSql(stripeAccountId, liveMode, params),
          sql"""
            SELECT COUNT(*) FROM groups
          """,
        ).as[Long]
      }
      .map(_.headOption.getOrElse(0L))
  }


  def get(stripeAccountId: String, liveMode: Boolean, params: Params, offset: Int, limit: Int): Future[Result] = {
    val resultColumns = getResultColumns(params)
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns)
    db
      .run {
        makeSql(
          makeBaseWithSql(stripeAccountId, liveMode, params),
          sql"""
            SELECT * FROM groups
          """,
          makeOrderByClause(params.sorts),
          sql"LIMIT $limit OFFSET $offset"
        ).as[Seq[Option[Any]]]
      }
      .map { rows =>
        Result(resultColumns, rows.toList)
      }
  }

  def exportToCsv(stripeAccountId: String, liveMode: Boolean, params: Params, destinationFile: File): Future[Unit] = {
    val resultColumns = getResultColumns(params).toArray
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns.toList)

    val writer = new BufferedWriter(new FileWriter(destinationFile, StandardCharsets.UTF_8))
    writer.write(resultColumns.map { column => escapeCsv(column.id.toString) }.mkString(","))
    writer.newLine()

    db
      .stream {
        makeSql(
          makeBaseWithSql(stripeAccountId, liveMode, params),
          sql"""
            SELECT * FROM groups
          """,
          makeOrderByClause(params.sorts),
        ).as[Seq[Option[Any]]]
      }
      .foreach { row =>
        var i = 0
        writer.write(
          row.map { r =>
            val result = formatCsvValue(r, resultColumns(i).tpe)
            i += 1
            result
          }.mkString(",")
        )
        writer.newLine()
      }
      .andThen { case _ => writer.close() }
  }
}

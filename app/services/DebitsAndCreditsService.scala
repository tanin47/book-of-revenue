package services

import database.models.JournalEntry
import database.services.JournalEntryService
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

object DebitsAndCreditsService {
  enum Column extends Enum[Column] {
    case
    AccountingPeriod,
    AttributionPeriod,
    Debit,
    Credit,
    Amount,
    Event,
    ProductId,
    ProductName,
    CustomerId,
    CustomerName,
    CustomerEmail,
    RevRecTransactionId,
    RevRecTransactionTitle,
    InvoiceId,
    InvoiceNumber,
    InvoiceLineItemDescription,
    InvoiceLineItemId,
    InvoiceLineItemStartedAt,
    InvoiceLineItemEndedAt,
    ReversedEvent,
    OccurredAt
  }
  case class Sort(column: Column, direction: SortDirection)
  enum GroupBy extends Enum[GroupBy] {
    case Summary, Product, Customer, Transaction, LineItem
  }
  case class Params(
    periodStart: Option[Instant],
    periodEnd: Option[Instant],
    transactionId: Option[String],
    groupBy: Option[GroupBy],
    currency: String,
    lineItemId: Option[String],
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

  case class AccountSummaryEntry(
    account: JournalEntry.Account,
    accountingPeriod: Instant,
    settlementAmount: Long,
    settlementCurrency: String,
    presentmentAmount: Long,
    presentmentCurrency: String,
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "account" -> account.toString,
      "accountingPeriod" -> accountingPeriod.toEpochMilli,
      "settlementAmount" -> settlementAmount,
      "settlementCurrency" -> settlementCurrency,
      "presentmentAmount" -> presentmentAmount,
      "presentmentCurrency" -> presentmentCurrency,
    )
  }
}

@Singleton
class DebitsAndCreditsService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  journalEntryService: JournalEntryService,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import DebitsAndCreditsService.*
  import framework.PostgresProfile.api.*


  private[this] def makeOrderByClause(sorts: Seq[Sort]): SQLActionBuilder = {
    if (sorts.isEmpty) {
      return sql"ORDER BY accounting_period ASC, debit ASC, credit ASC"
    }

    val sortClauses = sorts.map { sort =>
      val name = sort.column match {
        case Column.AccountingPeriod => "accounting_period"
        case Column.AttributionPeriod => "attribution_period"
        case Column.Debit => "debit"
        case Column.Credit => "credit"
        case Column.Amount => "amount"
        case Column.Event => "event"
        case Column.ReversedEvent => "reversed_event"
        case Column.OccurredAt => "occurred_at"
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
          case Column.Debit => sql"debit"
          case Column.Credit => sql"credit"
          case Column.Amount => sql"settlement_amount AS amount"
          case Column.Event => sql"event"
          case Column.ReversedEvent => sql"reversed_event"
          case Column.OccurredAt => sql"occurred_at"
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
          case Column.Debit => sql"debit"
          case Column.Credit => sql"credit"
          case Column.Amount => sql"SUM(settlement_amount) AS amount"
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
          case Column.Debit => ColumnType.String
          case Column.Credit => ColumnType.String
          case Column.Amount => ColumnType.Amount
          case Column.Event => ColumnType.String
          case Column.ReversedEvent => ColumnType.String
          case Column.OccurredAt => ColumnType.Timestamp
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
        makeSql(sql"GROUP BY accounting_period, debit, credit", extraGroupKey)
      }
      .getOrElse(sql"")
  }

  private[this] def makeBaseWithSql(stripeAccountId: String, liveMode: Boolean, params: Params): SQLActionBuilder = {
    val whereClause = joinSqls(
      Seq(
        Some(sql"j.stripe_account_id = $stripeAccountId"),
        Some(sql"j.live_mode = $liveMode"),
        Some(sql"j.settlement_currency = ${params.currency}"),
        params.periodStart.map { p => sql"accounting_period >= $p" },
        params.periodEnd.map { p => sql"accounting_period <= $p" },
        params.transactionId.map { c => sql"j.rev_rec_transaction_id = $c" },
        params.lineItemId.map { c => sql"j.invoice_line_item_id = $c" },
        if (params.accounts.nonEmpty) {
          Some(sql"(debit = ANY(${params.accounts}) OR credit = ANY(${params.accounts}))")
        } else {
          None
        }
      ).flatten,
      sql" AND "
    )

    makeSql(
      sql"""
        WITH entries AS (
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
          FROM journal_entry j
          LEFT JOIN customer cus ON cus.id = j.customer_id
          LEFT JOIN invoice inv ON inv.id = j.invoice_id
          LEFT JOIN invoice_line_item il ON il.id = j.invoice_line_item_id
          LEFT JOIN product pr ON pr.id = j.product_id
          LEFT JOIN rev_rec_transaction co ON co.id = j.rev_rec_transaction_id
          WHERE
      """,
      whereClause,
      sql"""
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

  def getAllAccounts(stripeAccountId: String, liveMode: Boolean): Future[Seq[String]] = {
    val whereClause = joinSqls(
      Seq(
        sql"stripe_account_id = $stripeAccountId",
        sql"live_mode = $liveMode"
      ),
      sql" AND "
    )
    db.run {
      makeSql(
        sql"""
          WITH debits AS (
            SELECT DISTINCT debit AS account FROM journal_entry WHERE
        """,
        whereClause,
        sql"""
          ), credits AS (
            SELECT DISTINCT credit AS account FROM journal_entry WHERE
        """,
        whereClause,
        sql"""
          ), combined AS (
            SELECT account FROM debits UNION ALL SELECT account FROM credits
          )

          SELECT DISTINCT account FROM combined ORDER BY account ASC
        """
      )
        .as[String]
    }
  }

  def getAccountSummary(
    stripeAccountId: String,
    liveMode: Boolean,
    transactionId: String,
    lineItemId: Option[String]
  ): Future[Seq[AccountSummaryEntry]] = {
    journalEntryService.getByRevRecTransactionId(stripeAccountId, liveMode, transactionId, lineItemId).map { entries =>
      entries
        .flatMap { entry =>
          Seq(
            AccountSummaryEntry(
              account = entry.debit,
              accountingPeriod = entry.accountingPeriod,
              settlementAmount = if (entry.debit.isCredit()) { -entry.settlementAmount } else { entry.settlementAmount },
              settlementCurrency = entry.settlementCurrency,
              presentmentAmount = if (entry.debit.isCredit()) { -entry.presentmentAmount } else { entry.presentmentAmount },
              presentmentCurrency = entry.presentmentCurrency,
            ),
            AccountSummaryEntry(
              account = entry.credit,
              accountingPeriod = entry.accountingPeriod,
              settlementAmount = if (entry.credit.isCredit()) { entry.settlementAmount } else { -entry.settlementAmount },
              settlementCurrency = entry.settlementCurrency,
              presentmentAmount = if (entry.credit.isCredit()) { entry.presentmentAmount } else { -entry.presentmentAmount },
              presentmentCurrency = entry.presentmentCurrency,
            ),
          )
        }
        .groupBy { entry => (entry.account, entry.accountingPeriod) }
        .values
        .map { entries =>
          entries.head.copy(
            settlementAmount = entries.map(_.settlementAmount).sum,
            presentmentAmount = entries.map(_.presentmentAmount).sum,
          )
        }
        .toList
    }
  }
}

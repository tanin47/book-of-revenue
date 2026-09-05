package services

import database.models.JournalEntry
import database.models.JournalEntry.{AccountCategory, getCategorySqlCond}
import database.services.JournalEntryService.{ColumnType, SortDirection, getValue}
import framework.Helpers.{escapeCsv, formatCsvValue}
import framework.{Instant, Jsonable, PlayConfig}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import process.Helpers.generatePeriods
import slick.jdbc.{GetResult, JdbcProfile, SQLActionBuilder}

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.charset.StandardCharsets
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

object BalanceSheetService {
  enum Column extends Enum[Column] {
    case
    Account,
    AccountingPeriod,
    Category,
    RevRecTransactionId,
    RevRecTransactionTitle,
    CustomerEmail,
    CustomerId,
    CustomerName,
    EndingBalance,
    Event,
    InvoiceId,
    InvoiceLineItemDescription,
    InvoiceLineItemEndedAt,
    InvoiceLineItemId,
    InvoiceLineItemStartedAt,
    InvoiceNumber,
    NetChange,
    ProductId,
    ProductName,
    StartingBalance
  }
  case class Sort(column: Column, direction: SortDirection)
  enum GroupBy extends Enum[GroupBy] {
    case Summary, Event, Product, Customer, Transaction, LineItem
  }
  enum GroupBy2 extends Enum[GroupBy2] {
    case Event
  }
  case class Params(
    periodStart: Option[Instant],
    periodEnd: Option[Instant],
    groupBy: Option[GroupBy],
    groupBy2: Option[GroupBy2],
    currency: String,
    showOnly: Option[Column],
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
    maxCharacterLength: Int
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "id" -> id.toString,
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

@Singleton
class BalanceSheetService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import BalanceSheetService.*
  import framework.PostgresProfile.api.*


  private[this] def makeOrderByClause(sorts: Seq[Sort]): SQLActionBuilder = {
    if (sorts.isEmpty) {
      return sql"ORDER BY accounting_period ASC, category ASC, net_settlement_change DESC, account ASC"
    }

    val sortClauses = sorts.map { sort =>
      val name = sort.column match {
        case Column.AccountingPeriod => "accounting_period"
        case Column.Account => "account"
        case Column.StartingBalance => "settlement_starting_balance"
        case Column.EndingBalance => "settlement_ending_balance"
        case Column.Category => "category"
        case Column.NetChange => "net_settlement_change"
        case Column.Event => "computed_event"
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
      params.columns.map {
        case Column.AccountingPeriod => sql"accounting_period"
        case Column.Account => sql"account"
        case Column.Category => sql"category"
        case Column.NetChange => sql"net_settlement_change"
        case Column.Event => sql"computed_event"
        case Column.ProductId => sql"product_id"
        case Column.ProductName => sql"product_name"
        case Column.CustomerId => sql"customer_id"
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
        case Column.StartingBalance => sql"settlement_starting_balance"
        case Column.EndingBalance => sql"settlement_ending_balance"
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
          case Column.Account => ColumnType.String
          case Column.StartingBalance => ColumnType.Amount
          case Column.EndingBalance => ColumnType.Amount
          case Column.Category => ColumnType.String
          case Column.NetChange => ColumnType.DeltaAmount
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
        maxCharacterLength = 0
      )
    }
  }

  private[this] def makeGroupKeys(params: Params): Seq[String] = {
    val base = Seq("accounting_period", "account")
    val extraGroupKeys = params.groupBy
      .map {
        case GroupBy.Product => Seq("product_id")
        case GroupBy.Event => Seq("computed_event")
        case GroupBy.Customer => Seq("customer_id")
        case GroupBy.Transaction => Seq("rev_rec_transaction_id")
        case GroupBy.LineItem => Seq("rev_rec_transaction_id", "invoice_line_item_id")
        case GroupBy.Summary => Seq.empty
      }
      .getOrElse(Seq.empty)
    val extraGroupKeys2 = params.groupBy2
      .map {
        case GroupBy2.Event => Seq("computed_event")
      }
      .getOrElse(Seq.empty)

    base ++ extraGroupKeys ++ extraGroupKeys2
  }

  def makeNetChangeSql(stripeAccountId: String, liveMode: Boolean, params: Params, forCumulative: Boolean): SQLActionBuilder = {
    def makeWhereClause(isDebit: Boolean) = joinSqls(
      Seq(
        Some(sql"stripe_account_id = $stripeAccountId"),
        Some(sql"live_mode = $liveMode"),
        if (!forCumulative) {
          params.periodStart.map { p => sql"accounting_period >= $p" }
        } else {
          None
        },
        params.periodEnd.map { p => sql"accounting_period <= $p" },
        params.productId.map { c => sql"product_id = $c" },
        params.customerId.map { c => sql"customer_id = $c" },
        params.transactionId.map { c => sql"rev_rec_transaction_id = $c" },
        if (params.accounts.nonEmpty) {
          val account = if (isDebit) {
            sql"debit"
          } else {
            sql"credit"
          }
          Some(makeSql(account, sql" = ANY(${params.accounts})"))
        } else {
          None
        }
      ).flatten,
      sql" AND "
    )

    val assetAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.Asset).toList
    val contraAssetAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.ContraAsset).toList
    val contractLiabilityAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.ContractLiability).toList
    val statutoryLiabilityAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.StatutoryLiability).toList

    val keys = makeGroupKeys(params)
    val groupByClause = joinSqls(keys.map { k => sql"#$k" }, sql", ")

    makeSql(
      sql"""
           WITH credit_entries AS (
             SELECT
               *,
               COALESCE(reversed_event, event) AS computed_event,
               credit as account,
               CASE
                 WHEN credit = ANY(${(assetAccounts ++ contraAssetAccounts).map(_.name)}) THEN -settlement_amount
                 WHEN credit = ANY(${(contractLiabilityAccounts ++ statutoryLiabilityAccounts).map(_.name)}) THEN settlement_amount
                 ELSE 0
               END AS net_settlement_change
             FROM journal_entry
             WHERE
         """,
      makeWhereClause(isDebit = false),
      sql"""
           ),
           debit_entries AS (
             SELECT
               *,
               COALESCE(reversed_event, event) AS computed_event,
               debit as account,
               CASE
                 WHEN debit = ANY(${(assetAccounts ++ contraAssetAccounts).map(_.name)}) THEN settlement_amount
                 WHEN debit = ANY(${(contractLiabilityAccounts ++ statutoryLiabilityAccounts).map(_.name)}) THEN -settlement_amount
                 ELSE 0
               END AS net_settlement_change
             FROM journal_entry
             WHERE
         """,
      makeWhereClause(isDebit = true),
      sql"""
           ),
           raw_entries AS (
             SELECT * FROM credit_entries UNION ALL SELECT * FROM debit_entries
           ),
           entries AS (
             SELECT * FROM raw_entries WHERE net_settlement_change != 0 AND settlement_currency = ${params.currency}
           ),

           net_changes AS (
             SELECT
               accounting_period,
               account,
               MAX(computed_event) AS computed_event,
               SUM(net_settlement_change) AS net_settlement_change,
               MAX(customer_id) AS customer_id,
               MAX(rev_rec_transaction_id) AS rev_rec_transaction_id,
               MAX(invoice_id) AS invoice_id,
               MAX(invoice_line_item_id) AS invoice_line_item_id,
               MAX(product_id) AS product_id
             FROM entries
             GROUP BY
         """,
      groupByClause,
      sql"""
           )
      """
    )
  }

  def makeBaseWithSql(stripeAccountId: String, liveMode: Boolean, params: Params): SQLActionBuilder = {
    val keys = makeGroupKeys(params)
    val cumulativeJoinKeyClause = joinSqls(keys.filter(_ != "accounting_period").map { k => sql"main.#$k IS NOT DISTINCT FROM sub.#$k" }, sql" AND ")
    val cumulativeGroupKeyClause = joinSqls(keys.map { k => sql"main.#$k" }, sql", ")
    val distinctOnKeyClause = joinSqls(keys.filter(_ != "accounting_period").map { k => sql"g.#$k" }, sql", ")

    val periods = generatePeriods(params.periodStart.get, params.periodEnd.get.plusMillis(1)).map(_.startedAt.getEpochSecond)

    val groupWhereClause = params.showOnly match {
      case Some(Column.StartingBalance) => sql"AND settlement_starting_balance != 0"
      case Some(Column.EndingBalance) => sql"AND settlement_ending_balance != 0"
      case Some(Column.NetChange) => sql"AND net_settlement_change != 0"
      case Some(_) => throw new Exception(s"showOnly is not supported: ${params.showOnly}")
      case None => sql""
    }

    makeSql(
      makeNetChangeSql(stripeAccountId, liveMode, params, forCumulative = true),
      sql"""
        ,

        raw_groups AS (
          SELECT
            main.accounting_period,
            main.account,
            (
      """,
      getCategorySqlCond("main.account"),
      sql"""
            ) AS category,
            SUM(CASE
              WHEN sub.accounting_period = main.accounting_period THEN sub.net_settlement_change
              ELSE 0
            END)  AS net_settlement_change,
            SUM(sub.net_settlement_change) - SUM(CASE
              WHEN sub.accounting_period = main.accounting_period THEN sub.net_settlement_change
              ELSE 0
            END) AS settlement_starting_balance,
            SUM(sub.net_settlement_change) AS settlement_ending_balance,
            MAX(main.computed_event) AS computed_event,
            MAX(main.customer_id) AS customer_id,
            MAX(main.rev_rec_transaction_id) AS rev_rec_transaction_id,
            MAX(main.invoice_id) AS invoice_id,
            MAX(main.invoice_line_item_id) AS invoice_line_item_id,
            MAX(main.product_id) AS product_id
          FROM net_changes main
          LEFT JOIN net_changes sub
          ON sub.accounting_period <= main.accounting_period AND
      """,
      cumulativeJoinKeyClause,
      sql"""
          GROUP BY
      """,
      cumulativeGroupKeyClause,
      sql"""
        ),

        periods AS (
          SELECT to_timestamp(period) AS period FROM unnest($periods) AS period
        ),

        unfiltered_groups AS (
          SELECT DISTINCT ON (p.period,
      """,
      distinctOnKeyClause,
      sql"""
          )
            g.account,
            g.category,
            p.period AS accounting_period,
            (CASE
              WHEN p.period = g.accounting_period THEN g.net_settlement_change
              ELSE 0
            END) AS net_settlement_change,
            (CASE
              WHEN p.period = g.accounting_period THEN g.settlement_starting_balance
              ELSE g.settlement_ending_balance
            END) AS settlement_starting_balance,
            g.settlement_ending_balance,
            g.computed_event,
            g.customer_id,
            g.rev_rec_transaction_id,
            g.invoice_id,
            g.invoice_line_item_id,
            g.product_id
          FROM raw_groups g
          JOIN periods p
          ON g.accounting_period <= p.period
          ORDER BY p.period,
      """,
      distinctOnKeyClause,
      sql"""
          , g.accounting_period DESC
        ),

        groups AS (
          SELECT * FROM unfiltered_groups WHERE true
      """,
      groupWhereClause,
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

  def makeBaseWithSqlWithLookupColumns(stripeAccountId: String, liveMode: Boolean, params: Params): SQLActionBuilder = {
    makeSql(
      makeBaseWithSql(stripeAccountId, liveMode, params),
      sql"""
        ,
        group_with_lookup_columns AS(
          SELECT
            main.*,
            cus.name AS customer_name,
            cus.email AS customer_email,
            inv.number AS invoice_number,
            il.description AS invoice_line_item_description,
            il.started_at AS invoice_line_item_started_at,
            il.ended_at AS invoice_line_item_ended_at,
            product.name AS product_name,
            co.title AS rev_rec_transaction_title
          FROM groups main
          LEFT JOIN customer cus ON cus.id = main.customer_id
          LEFT JOIN invoice inv ON inv.id = main.invoice_id
          LEFT JOIN invoice_line_item il ON il.id = main.invoice_line_item_id
          LEFT JOIN product ON product.id = main.product_id
          LEFT JOIN rev_rec_transaction co ON co.id = main.rev_rec_transaction_id
        )
      """
    )
  }

  private[this] def makeListSql(stripeAccountId: String, liveMode: Boolean, params: Params): SQLActionBuilder = {
    makeSql(
      makeBaseWithSqlWithLookupColumns(stripeAccountId, liveMode, params),
      sql"""
        SELECT
      """,
      makeSelectedColumns(params),
      sql"""
         FROM group_with_lookup_columns
      """,
      makeOrderByClause(params.sorts),
    )
  }

  def get(stripeAccountId: String, liveMode: Boolean, params: Params, offset: Int, limit: Int): Future[Result] = {
    val resultColumns = getResultColumns(params)
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns)
    db
      .run {
        makeSql(
          makeListSql(stripeAccountId, liveMode, params),
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
        makeListSql(stripeAccountId, liveMode, params).as[Seq[Option[Any]]]
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

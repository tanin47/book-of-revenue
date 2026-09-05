package services

import database.models.JournalEntry
import database.models.JournalEntry.AccountCategory
import database.services.JournalEntryService.{ColumnType, SortDirection, getValue}
import framework.Helpers.{escapeCsv, formatCsvValue}
import framework.{Instant, Jsonable, PeriodColumn, PlayConfig}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import process.Helpers.generatePeriods
import slick.jdbc.{GetResult, JdbcProfile, SQLActionBuilder}

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

object NetRevenueService {
  enum Column extends Enum[Column] {
    case
    AccountingPeriod,
    RevRecTransactionId,
    RevRecTransactionTitle,
    RevRecTransactionType,
    TransactionValue,
    TransactionStatus,
    TransactionDate,
    CreditNotes,
    Currency,
    CustomerEmail,
    CustomerId,
    CustomerName,
    Disputes,
    GrossRevenue,
    InvoiceId,
    InvoiceLineItemDescription,
    InvoiceLineItemEndedAt,
    InvoiceLineItemId,
    InvoiceLineItemStartedAt,
    InvoiceNumber,
    NetRevenue,
    ProductId,
    ProductName,
    Refunds,
    Total,
    Voids
  }
  case class Sort(column: Column, direction: SortDirection)
  enum GroupBy extends Enum[GroupBy] {
    case Summary, Product, Customer, Transaction, LineItem
  }
  enum ShowOnly extends Enum[ShowOnly] {
    case GrossRevenue, CreditNotes, Refunds, Disputes, Voids, NetRevenue
  }
  case class Params(
    periodStart: Instant,
    periodEnd: Instant,
    currency: String,
    groupBy: Option[GroupBy],
    showOnly: Option[ShowOnly],
    productId: Option[String],
    customerId: Option[String],
    transactionId: Option[String],
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

  case class RevenueByMonthSort(
    column: Column | PeriodColumn,
    direction: SortDirection
  )
  case class RevenueByMonthParams(
    keyword: String,
    periodStart: Instant,
    periodEnd: Instant,
    currency: String,
    groupBy: GroupBy,
    customerId: Option[String],
    sorts: Seq[RevenueByMonthSort]
  )
  case class RevenueByMonthResultColumn(
    id: Column | PeriodColumn,
    tpe: ColumnType,
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "id" -> (id match {
        case id: Column => id.name
        case period: PeriodColumn => period.name
      }),
      "type" -> tpe.toString,
    )
  }
  case class RevenueByMonthResult(
    columns: Seq[RevenueByMonthResultColumn],
    rows: Seq[Seq[Option[Any]]]
  )
  def makeGetResultForCustomerRevenueByMonth(resultColumns: Seq[RevenueByMonthResultColumn]): GetResult[Seq[Option[Any]]] = {
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
  case class DataPointValue(
    account: String,
    value: Long
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "id" -> account,
      "value" -> value
    )
  }

  case class DataPoint(
    period: Instant,
    values: Seq[DataPointValue]
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "period" -> period.toEpochMilli,
      "values" -> values.map(_.toJson())
    )
  }
}

@Singleton
class NetRevenueService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import NetRevenueService.*
  import framework.PostgresProfile.api.*


  private[this] def makeOrderByClause(sorts: Seq[Sort]): SQLActionBuilder = {
    if (sorts.isEmpty) {
      return sql"accounting_period ASC, net_revenue_net_income DESC NULLS LAST, Revenue_net_income DESC NULLS LAST, settlement_currency ASC"
    }

    val sortClauses = sorts.map { sort =>
      val name = sort.column match {
        case Column.AccountingPeriod => "accounting_period"
        case Column.RevRecTransactionId => "rev_rec_transaction_id"
        case Column.RevRecTransactionTitle => "rev_rec_transaction_title"
        case Column.RevRecTransactionType => "rev_rec_transaction_type"
        case Column.TransactionDate => "transaction_started_at"
        case Column.TransactionValue => "transaction_settlement_total_value"
        case Column.TransactionStatus => "transaction_status"
        case Column.CreditNotes => "CreditNotes_net_income"
        case Column.Currency => "settlement_currency"
        case Column.CustomerEmail => "customer_email"
        case Column.CustomerId => "customer_id"
        case Column.CustomerName => "customer_name"
        case Column.Disputes => "Disputes_net_income"
        case Column.GrossRevenue => "Revenue_net_income"
        case Column.InvoiceId => "invoice_id"
        case Column.InvoiceLineItemDescription => "invoice_line_item_description"
        case Column.InvoiceLineItemEndedAt => "invoice_line_item_ended_at"
        case Column.InvoiceLineItemId => "invoice_line_item_id"
        case Column.InvoiceLineItemStartedAt => "invoice_line_item_started_at"
        case Column.InvoiceNumber => "invoice_number"
        case Column.NetRevenue => "net_revenue_net_income"
        case Column.ProductId => "product_id"
        case Column.ProductName => "product_name"
        case Column.Total => "total"
        case Column.Refunds => "Refunds_net_income"
        case Column.Voids => "Voids_net_income"
      }

      sql"#${name} #${sort.direction.toString.toUpperCase} NULLS LAST"
    }

    joinSqls(sortClauses, sql", ")
  }

  private[this] def makeSelectedColumns(params: Params): SQLActionBuilder = {
    val computedColumns = params.columns ++ Seq(
      Column.AccountingPeriod,
      Column.Currency,
      Column.GrossRevenue,
      Column.CreditNotes,
      Column.Refunds,
      Column.Voids,
      Column.Disputes,
      Column.NetRevenue,
    ).filter { column => !params.columns.contains(column)}
    joinSqls(
      if (params.groupBy.isEmpty) {
        computedColumns.map {
          case Column.AccountingPeriod => sql"accounting_period"
          case Column.RevRecTransactionId => sql"rev_rec_transaction_id"
          case Column.RevRecTransactionTitle => sql"rev_rec_transaction_title"
          case Column.RevRecTransactionType => sql"rev_rec_transaction_type"
          case Column.TransactionDate => sql"transaction_started_at"
          case Column.TransactionValue => sql"transaction_settlement_total_value"
          case Column.TransactionStatus => sql"transaction_status"
          case Column.CreditNotes => sql"CreditNotes_net_income"
          case Column.Currency => sql"settlement_currency"
          case Column.CustomerEmail => sql"customer_email"
          case Column.CustomerId => sql"product_id"
          case Column.CustomerName => sql"customer_name"
          case Column.Disputes => sql"Disputes_net_income"
          case Column.GrossRevenue => sql"Revenue_net_income"
          case Column.InvoiceId => sql"invoice_id"
          case Column.InvoiceLineItemDescription => sql"invoice_line_item_description"
          case Column.InvoiceLineItemEndedAt => sql"invoice_line_item_ended_at"
          case Column.InvoiceLineItemId => sql"invoice_line_item_id"
          case Column.InvoiceLineItemStartedAt => sql"invoice_line_item_started_at"
          case Column.InvoiceNumber => sql"invoice_number"
          case Column.NetRevenue => sql"net_revenue_net_income"
          case Column.ProductId => sql"product_id"
          case Column.ProductName => sql"product_name"
          case Column.Refunds => sql"Refunds_net_income"
          case Column.Total => sql"total"
          case Column.Voids => sql"Voids_net_income"
        }
      } else {
        computedColumns.map {
          case Column.AccountingPeriod => sql"accounting_period"
          case Column.RevRecTransactionId => sql"MAX(rev_rec_transaction_id) AS rev_rec_transaction_id"
          case Column.RevRecTransactionTitle => sql"MAX(rev_rec_transaction_title) AS rev_rec_transaction_title"
          case Column.RevRecTransactionType => sql"MAX(rev_rec_transaction_type) AS rev_rec_transaction_type"
          case Column.TransactionDate => sql"MAX(transaction_started_at) AS transaction_started_at"
          case Column.TransactionValue => sql"MAX(transaction_settlement_total_value) AS transaction_settlement_total_value"
          case Column.TransactionStatus => sql"MAX(transaction_status) AS transaction_status"
          case Column.CreditNotes => sql"SUM(CreditNotes_net_income) AS CreditNotes_net_income"
          case Column.Currency => sql"settlement_currency"
          case Column.CustomerEmail => sql"MAX(customer_email) AS customer_email"
          case Column.CustomerId => sql"MAX(customer_id) AS customer_id"
          case Column.CustomerName => sql"MAX(customer_name) AS customer_name"
          case Column.Disputes => sql"SUM(Disputes_net_income) AS Disputes_net_income"
          case Column.GrossRevenue => sql"SUM(Revenue_net_income) AS Revenue_net_income"
          case Column.InvoiceId => sql"MAX(invoice_id) AS invoice_id"
          case Column.InvoiceLineItemDescription => sql"MAX(invoice_line_item_description) AS invoice_line_item_description"
          case Column.InvoiceLineItemEndedAt => sql"MAX(invoice_line_item_ended_at) AS invoice_line_item_ended_at"
          case Column.InvoiceLineItemId => sql"MAX(invoice_line_item_id) AS invoice_line_item_id"
          case Column.InvoiceLineItemStartedAt => sql"MIN(invoice_line_item_started_at) AS invoice_line_item_started_at"
          case Column.InvoiceNumber => sql"MAX(invoice_number) AS invoice_number"
          case Column.NetRevenue => sql"SUM(net_revenue_net_income) AS net_revenue_net_income"
          case Column.ProductId => sql"MAX(product_id) AS product_id"
          case Column.ProductName => sql"MAX(product_name) AS product_name"
          case Column.Refunds => sql"SUM(Refunds_net_income) AS Refunds_net_income"
          case Column.Total => sql"SUM(total) AS total"
          case Column.Voids => sql"SUM(Voids_net_income) AS Voids_net_income"
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
          case Column.CreditNotes => ColumnType.Amount
          case Column.Disputes => ColumnType.Amount
          case Column.GrossRevenue => ColumnType.Amount
          case Column.NetRevenue => ColumnType.Amount
          case Column.Currency => ColumnType.String
          case Column.Refunds => ColumnType.Amount
          case Column.Voids => ColumnType.Amount
          case Column.ProductId => ColumnType.String
          case Column.ProductName => ColumnType.String
          case Column.CustomerId => ColumnType.String
          case Column.CustomerName => ColumnType.String
          case Column.CustomerEmail => ColumnType.String
          case Column.RevRecTransactionId => ColumnType.String
          case Column.RevRecTransactionTitle => ColumnType.String
          case Column.RevRecTransactionType => ColumnType.String
          case Column.TransactionDate => ColumnType.Timestamp
          case Column.TransactionValue => ColumnType.Amount
          case Column.TransactionStatus => ColumnType.String
          case Column.InvoiceId => ColumnType.String
          case Column.InvoiceNumber => ColumnType.String
          case Column.InvoiceLineItemDescription => ColumnType.String
          case Column.InvoiceLineItemId => ColumnType.String
          case Column.InvoiceLineItemStartedAt => ColumnType.Timestamp
          case Column.InvoiceLineItemEndedAt => ColumnType.Timestamp
          case Column.Total => ColumnType.Amount
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
        makeSql(sql"GROUP BY settlement_currency, accounting_period", extraGroupKey)
      }
      .getOrElse(sql"")
  }

  def makeBaseWithSql(stripeAccountId: String, liveMode: Boolean, params: Params): SQLActionBuilder = {
    val whereClause = joinSqls(
      Seq(
        Some(sql"stripe_account_id = $stripeAccountId"),
        Some(sql"live_mode = $liveMode"),
        Some(sql"settlement_currency = ${params.currency}"),
        Some(sql"accounting_period >= ${params.periodStart}"),
        Some(sql"accounting_period <= ${params.periodEnd}"),
        params.productId.map { productId => sql"product_id = $productId" },
        params.customerId.map { customerId => sql"customer_id = $customerId" },
        params.transactionId.map { transactionId => sql"rev_rec_transaction_id = $transactionId" },
      ).flatten,
      sql" AND "
    )

    val revenueAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.Revenue).toList
    val contraRevenueAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.ContraRevenue).toList

    val groupWhereClause = params.showOnly
      .map {
        case ShowOnly.GrossRevenue => sql"Revenue_net_income != 0"
        case ShowOnly.NetRevenue => sql"net_revenue_net_income != 0"
        case ShowOnly.Disputes => sql"Disputes_net_income != 0"
        case ShowOnly.Refunds => sql"Refunds_net_income != 0"
        case ShowOnly.Voids => sql"Voids_net_income != 0"
        case ShowOnly.CreditNotes => sql"CreditNotes_net_income != 0"
      }
      .getOrElse(
        joinSqls(
          sqls = Seq(
            sql"Revenue_net_income != 0",
            sql"net_revenue_net_income != 0"
          ) ++
            contraRevenueAccounts.map { contraAccount => sql"#${contraAccount.name}_net_income != 0" },
          delim = sql"OR"
        )
      )

    val contraAccountClause = joinSqls(
      contraRevenueAccounts.map { contraAccount =>
        sql"""
          (
            (CASE WHEN credit = ${contraAccount.name} THEN settlement_amount ELSE 0 END) +
            (CASE WHEN debit = ${contraAccount.name} THEN -settlement_amount ELSE 0 END)
          ) AS #${contraAccount.name}_net_income
        """
      },
      sql","
    )

    makeSql(
      sql"""
        WITH raw_entries AS (
          SELECT
            *,
            (
              (CASE WHEN credit = 'Revenue' THEN settlement_amount ELSE 0 END) +
              (CASE WHEN debit = 'Revenue' THEN -settlement_amount ELSE 0 END)
            ) AS Revenue_net_income,
      """,
      contraAccountClause,
      sql"""
            ,
            (
              (CASE WHEN credit = ANY(${(revenueAccounts ++ contraRevenueAccounts).map(_.name)}) THEN settlement_amount ELSE 0 END) +
              (CASE WHEN debit = ANY(${(revenueAccounts ++ contraRevenueAccounts).map(_.name)}) THEN -settlement_amount ELSE 0 END)
            ) AS net_revenue_net_income
          FROM journal_entry
          WHERE
      """,
      whereClause,
      sql"""
        ),
        entries AS (
          SELECT
            j.*,
            con.title AS rev_rec_transaction_title,
            cus.name AS customer_name,
            cus.email AS customer_email,
            inv.number AS invoice_number,
            il.description AS invoice_line_item_description,
            il.started_at AS invoice_line_item_started_at,
            il.ended_at AS invoice_line_item_ended_at,
            product.name AS product_name
          FROM raw_entries j
          LEFT JOIN rev_rec_transaction con
          ON con.id = j.rev_rec_transaction_id
          LEFT JOIN customer cus
          ON cus.id = j.customer_id
          LEFT JOIN invoice inv
          ON inv.id = j.invoice_id
          LEFT JOIN invoice_line_item il
          ON il.id = j.invoice_line_item_id
          LEFT JOIN product
          ON product.id = j.product_id
      """,
      sql"""
        ),

        raw_groups AS (
          SELECT
      """,
      makeSelectedColumns(params),
      sql"""
          FROM entries
      """,
      makeGroupByClause(params),
      sql"""
        ),

        groups AS (
          SELECT * FROM raw_groups
          WHERE
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


  def get(stripeAccountId: String, liveMode: Boolean, params: Params, offset: Int, limit: Int): Future[Result] = {
    val resultColumns = getResultColumns(params)
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns)
    db
      .run {
        makeSql(
          makeBaseWithSql(stripeAccountId, liveMode, params),
          sql"""
            SELECT * FROM groups ORDER BY
          """,
          makeOrderByClause(params.sorts),
          sql"LIMIT $limit OFFSET $offset"
        ).as[Seq[Option[Any]]]
      }
      .map { rows =>
        Result(resultColumns, rows.toList)
      }
  }

  def exportToCsv(stripeAccountId: String, liveMode: Boolean, params: Params): Future[File] = {
    val resultColumns = getResultColumns(params).toArray
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns.toList)

    val destinationFile = Files.createTempFile("net-revenue", ".csv").toFile
    val writer = new BufferedWriter(new FileWriter(destinationFile, StandardCharsets.UTF_8))
    writer.write(resultColumns.map { column => escapeCsv(column.id.toString) }.mkString(","))
    writer.newLine()

    db
      .stream {
        makeSql(
          makeBaseWithSql(stripeAccountId, liveMode, params),
          sql"""
            SELECT * FROM groups ORDER BY
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
      .map { _ => destinationFile }
  }

  def getDataPoints(stripeAccountId: String, liveMode: Boolean, currency: String, periodStart: Instant, periodEnd: Instant): Future[Seq[DataPoint]] = {
    get(
        stripeAccountId = stripeAccountId,
        liveMode = liveMode,
        params = Params(
          periodStart = periodStart,
          periodEnd = periodEnd,
          currency = currency,
          groupBy = Some(NetRevenueService.GroupBy.Summary),
          showOnly = None,
          productId = None,
          customerId = None,
          transactionId = None,
          columns = Seq(
            Column.AccountingPeriod,
            Column.GrossRevenue,
            Column.CreditNotes,
            Column.Refunds,
            Column.Disputes,
            Column.Voids,
          ),
          sorts = Seq.empty
        ),
        offset = 0,
        limit = 100000
      )
      .map { result =>
        val entriesByPeriod = result.rows.groupBy(_.head.asInstanceOf[Option[Long]].get).view.mapValues(_.head).toMap

        generatePeriods(periodStart, periodEnd.plusMillis(1)).map { period =>
          DataPoint(
            period = period.startedAt,
            values = {
              val entry = entriesByPeriod.get(period.startedAt.toEpochMilli)
              Seq(
                DataPointValue(account = "GrossRevenue", value = entry.flatMap(_.apply(1)).asInstanceOf[Option[Long]].getOrElse(0L)),
                DataPointValue(account = "CreditNotes", value = entry.flatMap(_.apply(2)).asInstanceOf[Option[Long]].getOrElse(0L)),
                DataPointValue(account = "Refunds", value = entry.flatMap(_.apply(3)).asInstanceOf[Option[Long]].getOrElse(0L)),
                DataPointValue(account = "Disputes", value = entry.flatMap(_.apply(4)).asInstanceOf[Option[Long]].getOrElse(0L)),
                DataPointValue(account = "Voids", value = entry.flatMap(_.apply(5)).asInstanceOf[Option[Long]].getOrElse(0L)),
              )
            }
          )
        }
      }
  }

  private[this] def makeRevenueByMonthOrderByClause(sorts: Seq[RevenueByMonthSort], groupBy: GroupBy): SQLActionBuilder = {
    if (sorts.isEmpty) {
      groupBy match {
        case GroupBy.Product => return sql"total DESC NULLS LAST, product_name ASC"
        case GroupBy.Customer => return sql"total DESC NULLS LAST, customer_name ASC"
        case GroupBy.Transaction => return sql"transaction_started_at DESC NULLS LAST, transaction_settlement_total_value DESC"
        case _ => throw new IllegalArgumentException(s"Invalid groupBy: ${groupBy}")
      }
    }

    joinSqls(
      sorts.map { sort =>
        sort.column match {
          case p: PeriodColumn => sql""""#${p.name}" #${sort.direction.toString.toUpperCase} NULLS LAST"""
          case c: Column => makeOrderByClause(Seq(Sort(c, sort.direction)))
        }
      },
      sql","
    )
  }

  private[this] def makeBaseRevenueByMonthWithSql(stripeAccountId: String, liveMode: Boolean, params: RevenueByMonthParams): SQLActionBuilder = {
    val periods = generatePeriods(params.periodStart, params.periodEnd.plusMillis(1))
    val sumPeriodColumnsSql = joinSqls(
      periods.map { period =>
        sql"""
          SUM(CASE WHEN accounting_period = ${period.startedAt} THEN net_revenue_net_income ELSE 0 END) AS "#${PeriodColumn(period.startedAt.toEpochMilli).name}"
        """
      },
      sql", "
    )

    val periodColumnsSql = joinSqls(
      periods.map { period => sql""""#${PeriodColumn(period.startedAt.toEpochMilli).name}"""" },
      sql", "
    )

    val keywordCond = if (params.keyword.isEmpty) {
      sql""
    } else {
      val modifiedKeyword = s"%${params.keyword}%"
      sql"""AND (customer_id ilike $modifiedKeyword OR c.name ilike $modifiedKeyword OR c.email ilike $modifiedKeyword)"""
    }

    val baseSql = makeBaseWithSql(
      stripeAccountId = stripeAccountId,
      liveMode = liveMode,
      params = Params(
        periodStart = params.periodStart,
        periodEnd = params.periodEnd,
        currency = params.currency,
        groupBy = Some(params.groupBy),
        showOnly = None,
        productId = None,
        customerId = None,
        transactionId = None,
        columns = Seq(
          Column.AccountingPeriod,
          Column.CustomerId,
          Column.RevRecTransactionId,
          Column.ProductId,
          Column.NetRevenue,
        ),
        sorts = Seq.empty
      )
    )

    params.groupBy match {
      case GroupBy.Product =>
        makeSql(
          baseSql,
          sql"""
            ,
            raw_revenue_by_month_groups AS (
              SELECT
                product_id,
                SUM(net_revenue_net_income) AS total,
          """,
          sumPeriodColumnsSql,
          sql"""
              FROM groups
              GROUP BY product_id
            ),

            revenue_by_month_groups AS (
              SELECT
                COALESCE(r.product_id, p.id) AS product_id,
                p.name AS product_name,
                total,
          """,
          periodColumnsSql,
          sql"""
              FROM
                raw_revenue_by_month_groups r
                LEFT JOIN product p
                ON p.id = r.product_id
          """,
          keywordCond,
          sql"""
            )
          """
        )
      case GroupBy.Customer =>
        makeSql(
          baseSql,
          sql"""
            ,
            raw_revenue_by_month_groups AS (
              SELECT
                customer_id,
                SUM(net_revenue_net_income) AS total,
          """,
          sumPeriodColumnsSql,
          sql"""
              FROM groups
              GROUP BY customer_id
            ),

            revenue_by_month_groups AS (
              SELECT
                COALESCE(r.customer_id, c.id) AS customer_id,
                c.name AS customer_name,
                c.email AS customer_email,
                total,
          """,
          periodColumnsSql,
          sql"""
              FROM
                customer c
                FULL OUTER JOIN raw_revenue_by_month_groups r
                ON c.id = r.customer_id
              WHERE c.stripe_account_id = $stripeAccountId AND c.live_mode = $liveMode
          """,
          keywordCond,
          sql"""
            )
          """
        )
      case GroupBy.Transaction =>
        val customerCond = params.customerId match {
          case None => sql"customer_id IS NULL"
          case Some(customerId) => sql"customer_id = $customerId"
        }
        makeSql(
          baseSql,
          sql"""
            ,
            raw_revenue_by_month_groups AS (
              SELECT
                rev_rec_transaction_id,
                SUM(net_revenue_net_income) AS total,
          """,
          sumPeriodColumnsSql,
          sql"""
              FROM groups
              GROUP BY rev_rec_transaction_id
            ),

            revenue_by_month_groups AS (
              SELECT
                COALESCE(r.rev_rec_transaction_id, c.id) AS rev_rec_transaction_id,
                c.title AS rev_rec_transaction_title,
                c.settlement_total_value AS transaction_settlement_total_value,
                c.type AS rev_rec_transaction_type,
                c.status AS transaction_status,
                c.started_at AS transaction_started_at,
          """,
          periodColumnsSql,
          sql"""
              FROM
                rev_rec_transaction c
                FULL OUTER JOIN raw_revenue_by_month_groups r
                ON c.id = r.rev_rec_transaction_id
              WHERE c.stripe_account_id = $stripeAccountId AND c.live_mode = $liveMode AND
          """,
          customerCond,
          sql"""
            )
          """
        )
      case _ => throw new IllegalArgumentException(s"Invalid groupBy: ${params.groupBy}")
    }
  }

  def countRevenueByMonth(
    stripeAccountId: String,
    liveMode: Boolean,
    params: RevenueByMonthParams,
  ): Future[Long] = {
    db
      .run {
        makeSql(
          makeBaseRevenueByMonthWithSql(stripeAccountId, liveMode, params),
          sql"""
            SELECT COUNT(*) FROM revenue_by_month_groups
          """,
        ).as[Long]
      }
      .map(_.headOption.getOrElse(0L))
  }

  private[this] def getRevenueByMonthResultColumns(params: RevenueByMonthParams): Seq[RevenueByMonthResultColumn] = {
    val baseColumns = params.groupBy match {
      case GroupBy.Product =>
        Seq(
          RevenueByMonthResultColumn(id = Column.ProductId, tpe = ColumnType.String),
          RevenueByMonthResultColumn(id = Column.ProductName, tpe = ColumnType.String),
          RevenueByMonthResultColumn(id = Column.Total, tpe = ColumnType.Amount),
        )
      case GroupBy.Customer =>
        Seq(
          RevenueByMonthResultColumn(id = Column.CustomerId, tpe = ColumnType.String),
          RevenueByMonthResultColumn(id = Column.CustomerName, tpe = ColumnType.String),
          RevenueByMonthResultColumn(id = Column.CustomerEmail, tpe = ColumnType.String),
          RevenueByMonthResultColumn(id = Column.Total, tpe = ColumnType.Amount),
        )
      case GroupBy.Transaction =>
        Seq(
          RevenueByMonthResultColumn(id = Column.RevRecTransactionId, tpe = ColumnType.String),
          RevenueByMonthResultColumn(id = Column.RevRecTransactionTitle, tpe = ColumnType.String),
          RevenueByMonthResultColumn(id = Column.TransactionValue, tpe = ColumnType.Amount),
          RevenueByMonthResultColumn(id = Column.RevRecTransactionType, tpe = ColumnType.String),
          RevenueByMonthResultColumn(id = Column.TransactionStatus, tpe = ColumnType.String),
          RevenueByMonthResultColumn(id = Column.TransactionDate, tpe = ColumnType.Timestamp),
        )
      case _ => throw new IllegalArgumentException(s"Invalid groupBy: ${params.groupBy}")
    }

    val periods = generatePeriods(params.periodStart, params.periodEnd.plusMillis(1))
    baseColumns ++ periods.map { period =>
      RevenueByMonthResultColumn(id = PeriodColumn(period.startedAt.toEpochMilli), tpe = ColumnType.Amount)
    }
  }

  def getRevenueByMonth(
    stripeAccountId: String,
    liveMode: Boolean,
    params: RevenueByMonthParams,
    offset: Int,
    limit: Int
  ): Future[RevenueByMonthResult] = {
    val resultColumns = getRevenueByMonthResultColumns(params)
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResultForCustomerRevenueByMonth(resultColumns)
    db
      .run {
        makeSql(
          makeBaseRevenueByMonthWithSql(stripeAccountId, liveMode, params),
          sql"""
            SELECT * FROM revenue_by_month_groups ORDER BY
          """,
          makeRevenueByMonthOrderByClause(params.sorts, params.groupBy),
          sql"LIMIT $limit OFFSET $offset"
        ).as[Seq[Option[Any]]]
      }
      .map { rows =>
        RevenueByMonthResult(resultColumns, rows.toList)
      }
  }

  def exportRevenueByMonthToCsv(stripeAccountId: String, liveMode: Boolean, params: RevenueByMonthParams): Future[File] = {
    val resultColumns = getRevenueByMonthResultColumns(params).toArray
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResultForCustomerRevenueByMonth(resultColumns.toList)

    val destinationFile = Files.createTempFile("customer-revenue-by-month", ".csv").toFile

    val writer = new BufferedWriter(new FileWriter(destinationFile, StandardCharsets.UTF_8))
    writer.write(resultColumns.map { column => escapeCsv(column.id.toString) }.mkString(","))
    writer.newLine()

    db
      .stream {
        makeSql(
          makeBaseRevenueByMonthWithSql(stripeAccountId, liveMode, params),
          sql"""
                SELECT * FROM revenue_by_month_groups ORDER BY
              """,
          makeRevenueByMonthOrderByClause(params.sorts, params.groupBy),
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
      .map { _ => destinationFile }
  }
}

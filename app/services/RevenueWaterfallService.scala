package services

import database.models.JournalEntry
import database.models.JournalEntry.AccountCategory
import database.services.JournalEntryService.{ColumnType, SortDirection, getValue}
import framework.Helpers.{escapeCsv, formatCsvValue}
import framework.{Instant, Jsonable, PeriodColumn, PlayConfig}
import givers.form.{BindContext, Mapping, UnbindContext}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.*
import process.Helpers.{generatePeriods, getNextAccountingPeriod}
import slick.jdbc.{GetResult, JdbcProfile, SQLActionBuilder}

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.charset.StandardCharsets
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{LocalDate, ZoneOffset}
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object RevenueWaterfallService {
  enum Column extends Enum[Column] {
    case
      BookedAccountingPeriod,
      ProductId,
      ProductName,
      CustomerId,
      CustomerName,
      CustomerEmail,
      RevRecTransactionId,
      RevRecTransactionTitle,
      InvoiceId,
      InvoiceNumber,
      InvoiceLineItemId,
      InvoiceLineItemDescription,
      InvoiceLineItemStartedAt,
      InvoiceLineItemEndedAt,
      Total
  }

  case class Sort(column: Column | PeriodColumn, direction: SortDirection)
  enum GroupBy extends Enum[GroupBy] {
    case Summary, Product, Customer, Transaction, LineItem
  }
  case class Params(
    startAccountingPeriod: Instant,
    endAccountingPeriod: Instant,
    currency: String,
    groupBy: GroupBy,
    productId: Option[String],
    customerId: Option[String],
    transactionId: Option[String],
    columns: Seq[Column],
    sorts: Seq[services.RevenueWaterfallService.Sort],
  )

  case class ResultColumn(
    id: Column | PeriodColumn,
    tpe: ColumnType,
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "id" -> id.toString,
      "type" -> tpe.toString
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

class RevenueWaterfallService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import RevenueWaterfallService.*
  import framework.PostgresProfile.api.*

  private[this] def makeOrderByClause(sorts: Seq[Sort]): SQLActionBuilder = {
    if (sorts.isEmpty) {
      return sql"ORDER BY booked_accounting_period ASC"
    }

    val sortClauses = sorts.map { sort =>
      val name = sort.column match {
        case Column.BookedAccountingPeriod => "booked_accounting_period"
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
        case Column.Total => "total_settlement_amount"
        case c: PeriodColumn => c.name
      }
      sql""""#${name}" #${sort.direction.toString.toUpperCase}"""
    }

    makeSql(sql"ORDER BY ", joinSqls(sortClauses, sql", "))
  }
  private[this] def makeGroupByClause(params: Params): SQLActionBuilder = {
    val baseGroupKey = sql"GROUP BY booked_accounting_period"
    val extraGroupKey = params.groupBy match {
      case GroupBy.Product => sql", product_id"
      case GroupBy.Customer => sql", customer_id"
      case GroupBy.Transaction => sql", rev_rec_transaction_id"
      case GroupBy.LineItem => sql", rev_rec_transaction_id, invoice_line_item_id"
      case GroupBy.Summary => sql""
    }

    makeSql(baseGroupKey, extraGroupKey)
  }

  private[this] def makeSelectedColumns(resultColumns: Seq[ResultColumn]): SQLActionBuilder = {
    joinSqls(
      resultColumns.map { column =>
        column.id match {
          case Column.BookedAccountingPeriod => sql"booked_accounting_period"
          case Column.ProductId => sql"product_id"
          case Column.ProductName => sql"product_name"
          case Column.CustomerId => sql"customer_id"
          case Column.CustomerName => sql"customer_name"
          case Column.CustomerEmail => sql"customer_email"
          case Column.RevRecTransactionId => sql"rev_rec_transaction_id"
          case Column.RevRecTransactionTitle => sql"rev_rec_transaction_title"
          case Column.InvoiceId => sql"invoice_id"
          case Column.InvoiceNumber => sql"invoice_number"
          case Column.InvoiceLineItemId => sql"invoice_line_item_id"
          case Column.InvoiceLineItemDescription => sql"invoice_line_item_description"
          case Column.InvoiceLineItemStartedAt => sql"invoice_line_item_started_at"
          case Column.InvoiceLineItemEndedAt => sql"invoice_line_item_ended_at"
          case Column.Total => sql"total_settlement_amount"
          case c: PeriodColumn => sql""""#${c.name}""""
        }
      },
      sql", "
    )
  }

  private def makeEntriesSql(stripeAccountId: String, liveMode: Boolean, params: Params): SQLActionBuilder = {
    val revenueAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.Revenue).toList
    val contraRevenueAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.ContraRevenue).toList

    val whereClause = joinSqls(
      Seq(
        Some(sql"""stripe_account_id = $stripeAccountId"""),
        Some(sql"""live_mode = $liveMode"""),
        Some(sql"""settlement_currency = ${params.currency}"""),
        Some(sql"""occurred_at >= ${params.startAccountingPeriod}"""),
        Some(sql"""occurred_at < ${getNextAccountingPeriod(params.endAccountingPeriod)}"""),
        params.productId.map { productId => sql"""product_id = $productId""" },
        params.customerId.map { customerId => sql"""customer_id = $customerId""" },
        params.transactionId.map { transactionId => sql"""rev_rec_transaction_id = $transactionId""" },
      ).flatten,
      sql" AND "
    )

    makeSql(
      sql"""
        SELECT
          accounting_period AS recognized_accounting_period,
          rev_rec_transaction_id,
          customer_id,
          invoice_line_item_id,
          product_id,
          DATE_TRUNC('month', occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS booked_accounting_period,
          MAX(invoice_id) AS invoice_id,
          SUM(
            (CASE
              WHEN debit = ANY(${(revenueAccounts ++ contraRevenueAccounts).map(_.name)}) THEN -settlement_amount
              ELSE 0
            END) +
            (CASE
              WHEN credit = ANY(${(revenueAccounts ++ contraRevenueAccounts).map(_.name)}) THEN settlement_amount
              ELSE 0
            END)
          ) AS settlement_amount
        FROM journal_entry
        WHERE
      """,
      whereClause,
      sql"""
        GROUP BY
          accounting_period,
          DATE_TRUNC('month', occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC',
          rev_rec_transaction_id,
          customer_id,
          invoice_line_item_id,
          product_id
      """
    )
  }

  private def makeBaseSql(stripeAccountId: String, liveMode: Boolean, params: Params, resultColumns: Seq[ResultColumn]): SQLActionBuilder = {
    val periodColumnsSql = joinSqls(
      resultColumns.flatMap { column =>
        column.id match {
          case c: PeriodColumn => Some(
            sql"""
              SUM(CASE
                WHEN recognized_accounting_period = ${Instant.ofEpochMilli(c.period)}
                THEN settlement_amount
                ELSE 0
              END) AS "#${c.name}"
            """
          )
          case _ => None
        }
      },
      sql", "
    )

    val periodColumnsWhere = joinSqls(
      resultColumns.flatMap { column =>
        column.id match {
          case c: PeriodColumn => Some(sql""""#${c.name}" != 0""")
          case _ => None
        }
      },
      sql" OR "
    )

    makeSql(
      sql"""
        WITH entries AS (
      """,
      makeEntriesSql(stripeAccountId, liveMode, params),
      sql"""
        ),

        raw_groups AS (
          SELECT
            booked_accounting_period,
            MAX(rev_rec_transaction_id) AS rev_rec_transaction_id,
            MAX(customer_id) AS customer_id,
            MAX(product_id) AS product_id,
            MAX(invoice_id) AS invoice_id,
            MAX(invoice_line_item_id) AS invoice_line_item_id,
            sum(settlement_amount) AS total_settlement_amount,
      """,
      periodColumnsSql,
      sql"FROM entries",
      makeGroupByClause(params),
      sql"""
        ),

        groups AS (
          SELECT * FROM raw_groups WHERE total_settlement_amount != 0 OR
      """,
      periodColumnsWhere,
      sql"""
        )
      """
    )
  }

  private def getMinMaxAccountPeriod(stripeAccountId: String, liveMode: Boolean, params: Params): Future[Option[(Option[Instant], Option[Instant])]] = {
    implicit val getInstant: GetResult[Option[Instant]] = GetResult(r => Option(r.nextTimestamp()).map(_.toInstant))
    db.run {
      makeSql(
        sql"with entries AS (",
        makeEntriesSql(stripeAccountId, liveMode, params),
        sql"""
          )

          SELECT MIN(recognized_accounting_period), MAX(recognized_accounting_period) FROM entries WHERE settlement_amount != 0
        """
      ).as[(Option[Instant], Option[Instant])]
    }
      .map { results =>
        results.headOption
      }
  }

  private[this] def getResultColumns(stripeAccountId: String, liveMode: Boolean, params: Params): Future[Seq[ResultColumn]] = {
    for {
      minMaxAccountPeriod <- getMinMaxAccountPeriod(stripeAccountId, liveMode, params)
    } yield {
      val minPeriod = minMaxAccountPeriod.flatMap(_._1).getOrElse(params.startAccountingPeriod)
      val maxPeriod = minMaxAccountPeriod.flatMap(_._2).getOrElse(params.endAccountingPeriod)

      val periods = generatePeriods(minPeriod, maxPeriod.plusSeconds(1)).map { p =>
        PeriodColumn(p.startedAt.toEpochMilli)
      }

      val baseColumns = params.columns.map { column =>
        ResultColumn(
          id = column,
          tpe = column match {
            case Column.BookedAccountingPeriod => ColumnType.Period
            case Column.ProductId => ColumnType.String
            case Column.ProductName => ColumnType.String
            case Column.CustomerId => ColumnType.String
            case Column.CustomerName => ColumnType.String
            case Column.CustomerEmail => ColumnType.String
            case Column.RevRecTransactionId => ColumnType.String
            case Column.RevRecTransactionTitle => ColumnType.String
            case Column.InvoiceId => ColumnType.String
            case Column.InvoiceNumber => ColumnType.String
            case Column.InvoiceLineItemId => ColumnType.String
            case Column.InvoiceLineItemDescription => ColumnType.String
            case Column.InvoiceLineItemStartedAt => ColumnType.Timestamp
            case Column.InvoiceLineItemEndedAt => ColumnType.Timestamp
            case Column.Total => ColumnType.Amount
          },
        )
      }.sortBy { column =>
        column.id match {
          case Column.BookedAccountingPeriod => 1
          case Column.Total => 100
          case _ => 2
        }
      }

      val recognizedAccountingPeriodColumns = periods.map { column =>
        ResultColumn(
          id = column,
          tpe = ColumnType.Amount,
        )
      }

      baseColumns ++ recognizedAccountingPeriodColumns
    }
  }

  def count(stripeAccountId: String, liveMode: Boolean, params: Params): Future[Long] = {
    db
      .run {
        makeSql(
          makeBaseSql(stripeAccountId, liveMode, params, Seq(ResultColumn(Column.BookedAccountingPeriod, ColumnType.String), ResultColumn(PeriodColumn(0), ColumnType.Amount))),
          sql"""
            SELECT COUNT(*) FROM groups
          """
        ).as[Long]

      }
      .map { result =>
        result.headOption.getOrElse(0L)
      }
  }

  private[this] def makeListSql(stripeAccountId: String, liveMode: Boolean, params: Params, resultColumns: Seq[ResultColumn]): SQLActionBuilder = {
    makeSql(
      makeBaseSql(stripeAccountId, liveMode, params, resultColumns),
      sql"""
        ,

        group_with_infos AS (
          SELECT
            main.*,
            p.name AS product_name,
            c.name AS customer_name,
            c.email AS customer_email,
            co.title AS rev_rec_transaction_title,
            i.number AS invoice_number,
            il.description AS invoice_line_item_description,
            il.started_at AS invoice_line_item_started_at,
            il.ended_at AS invoice_line_item_ended_at
          FROM groups AS main
          LEFT JOIN product AS p ON p.id = main.product_id
          LEFT JOIN customer AS c ON c.id = main.customer_id
          LEFT JOIN rev_rec_transaction AS co ON co.id = main.rev_rec_transaction_id
          LEFT JOIN invoice AS i ON i.id = main.invoice_id
          LEFT JOIN invoice_line_item AS il ON il.id = main.invoice_line_item_id
        )

      """,
      sql"SELECT",
      makeSelectedColumns(resultColumns),
      sql"FROM group_with_infos",
      makeOrderByClause(params.sorts),
    )
  }

  def get(stripeAccountId: String, liveMode: Boolean, params: Params, offset: Int, limit: Int): Future[Result] = {
    for {
      resultColumns <- getResultColumns(stripeAccountId, liveMode, params)
      result <- {
        implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns)

        db
          .run {
            makeSql(
              makeListSql(stripeAccountId, liveMode, params, resultColumns),
              sql"LIMIT $limit OFFSET $offset"
            ).as[Seq[Option[Any]]]
          }
          .map { rows =>
            Result(resultColumns, rows.toList)
          }
      }
    } yield {
      result
    }
  }

  def exportToCsv(stripeAccountId: String, liveMode: Boolean, params: Params, destinationFile: File): Future[Unit] = {
    for {
      resultColumns <- getResultColumns(stripeAccountId, liveMode, params).map(_.toArray)
      _ <- {
        implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns.toList)

        val writer = new BufferedWriter(new FileWriter(destinationFile, StandardCharsets.UTF_8))
        writer.write(resultColumns.map { column => escapeCsv(column.id.toString) }.mkString(","))
        writer.newLine()

        db
          .stream {
            makeListSql(stripeAccountId, liveMode, params, resultColumns.toList).as[Seq[Option[Any]]]
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
    } yield {
      ()
    }
  }
}

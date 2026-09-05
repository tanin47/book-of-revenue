package services

import database.services.JournalEntryService.{ColumnType, SortDirection, getValue}
import framework.Helpers.{escapeCsv, formatCsvValue}
import framework.{Instant, Jsonable, PlayConfig}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import slick.jdbc.{GetResult, JdbcProfile, SQLActionBuilder}

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

object ArAgingService {
  enum Column extends Enum[Column] {
    case
      Date,
      NotDue,
      Days30,
      Days60,
      Days90,
      Days120,
      Days120Plus,
      Total,
      OccurredAt,
      CustomerId,
      CustomerName,
      CustomerEmail,
      RevRecTransactionId,
      RevRecTransactionTitle,
      InvoiceId,
      InvoiceNumber
  }

  case class Sort(column: Column, direction: SortDirection)
  enum GroupBy extends Enum[GroupBy] {
    case Summary, Customer, Transaction
  }
  case class Params(
    exclusiveUpUntil: Instant,
    groupBy: GroupBy,
    currency: String,
    customerId: Option[String],
    columns: Seq[Column],
    sorts: Seq[ArAgingService.Sort],
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

class ArAgingService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import ArAgingService.*
  import framework.PostgresProfile.api.*

  private[this] def makeOrderByClause(sorts: Seq[Sort]): SQLActionBuilder = {
    if (sorts.isEmpty) {
      return sql"ORDER BY total DESC, days_120_plus DESC, days_120 DESC, days_90 DESC, days_60 DESC, days_30 DESC"
    }

    val sortClauses = sorts.map { sort =>
      val name = sort.column match {
        case Column.Date => "date"
        case Column.NotDue => "not_due"
        case Column.Days30 => "days_30"
        case Column.Days60 => "days_60"
        case Column.Days90 => "days_90"
        case Column.Days120 => "days_120"
        case Column.Days120Plus => "days_120_plus"
        case Column.Total => "total"
        case Column.OccurredAt => "occurred_at"
        case Column.CustomerId => "customer_id"
        case Column.CustomerName => "customer_name"
        case Column.CustomerEmail => "customer_email"
        case Column.RevRecTransactionId => "rev_rec_transaction_id"
        case Column.RevRecTransactionTitle => "rev_rec_transaction_title"
        case Column.InvoiceId => "invoice_id"
        case Column.InvoiceNumber => "invoice_number"
      }

      sql"#${name} #${sort.direction.toString.toUpperCase}"
    }

    makeSql(sql"ORDER BY ", joinSqls(sortClauses, sql", "))
  }
  private[this] def makeGroupByClause(params: Params): SQLActionBuilder = {
    params.groupBy match {
      case GroupBy.Customer => sql"GROUP BY customer_id"
      case GroupBy.Transaction => sql"GROUP BY rev_rec_transaction_id"
      case GroupBy.Summary => sql""
    }
  }

  private[this] def makeSelectedColumns(params: Params): SQLActionBuilder = {
    joinSqls(
      params.columns.map {
        case Column.Date => sql"${params.exclusiveUpUntil.minusMillis(1)} AS date"
        case Column.NotDue => sql"SUM(not_due) AS not_due"
        case Column.Days30 => sql"SUM(days_30) AS days_30"
        case Column.Days60 => sql"SUM(days_60) AS days_60"
        case Column.Days90 => sql"SUM(days_90) AS days_90"
        case Column.Days120 => sql"SUM(days_120) AS days_120"
        case Column.Days120Plus => sql"SUM(days_120_plus) AS days_120_plus"
        case Column.Total => sql"SUM(total) AS total"
        case Column.OccurredAt => sql"MIN(occurred_at) AS occurred_at"
        case Column.CustomerId => sql"MIN(customer_id) AS customer_id"
        case Column.CustomerName => sql"MIN(customer_name) AS customer_name"
        case Column.CustomerEmail => sql"MIN(customer_email) AS customer_email"
        case Column.RevRecTransactionId => sql"MIN(rev_rec_transaction_id) AS rev_rec_transaction_id"
        case Column.RevRecTransactionTitle => sql"MIN(rev_rec_transaction_title) AS rev_rec_transaction_title"
        case Column.InvoiceId => sql"MIN(invoice_id) AS invoice_id"
        case Column.InvoiceNumber => sql"MIN(invoice_number) AS invoice_number"
      },
      sql", "
    )
  }

  private def makeBaseSql(stripeAccountId: String, liveMode: Boolean, params: Params): SQLActionBuilder = {
    val whereClause = joinSqls(
      Seq(
        Some(sql"stripe_account_id = $stripeAccountId"),
        Some(sql"live_mode = $liveMode"),
        Some(sql"occurred_at <= ${params.exclusiveUpUntil}"),
        Some(sql"settlement_currency = ${params.currency}"),
        Some(sql"'AccountsReceivable' IN (debit, credit)"),
        params.customerId.map(customerId => sql"customer_id = $customerId"),
      ).flatten,
      sql" AND "
    )

    makeSql(
      sql"""
        WITH entries AS (
          SELECT
            rev_rec_transaction_id,
            customer_id,
            MIN(invoice_id) AS invoice_id,
            EXTRACT(DAY FROM (${params.exclusiveUpUntil} - MIN(occurred_at)))::INTEGER AS days_outstanding,
            MIN(occurred_at) AS occurred_at,
            SUM(
              (CASE WHEN debit = 'AccountsReceivable' THEN settlement_amount ELSE 0 END) +
              (CASE WHEN credit = 'AccountsReceivable' THEN -settlement_amount ELSE 0 END)
            ) AS amount
          FROM journal_entry
          WHERE
      """,
      whereClause,
      sql"""
          GROUP BY settlement_currency, customer_id, rev_rec_transaction_id
          HAVING SUM(
            (CASE WHEN debit = 'AccountsReceivable' THEN settlement_amount ELSE 0 END) +
              (CASE WHEN credit = 'AccountsReceivable' THEN -settlement_amount ELSE 0 END)
          ) != 0
        ),
        bucketed AS (
          SELECT
            e.rev_rec_transaction_id,
            e.customer_id,
            e.invoice_id,
            e.occurred_at,
            cus.name AS customer_name,
            cus.email AS customer_email,
            inv.number AS invoice_number,
            con.title AS rev_rec_transaction_title,
            CASE WHEN days_outstanding <= 0 THEN e.amount ELSE 0 END AS "not_due",
            CASE WHEN days_outstanding > 0 AND days_outstanding <= 30 THEN e.amount ELSE 0 END AS "days_30",
            CASE WHEN days_outstanding > 30 AND days_outstanding <= 60 THEN e.amount ELSE 0 END AS "days_60",
            CASE WHEN days_outstanding > 60 AND days_outstanding <= 90 THEN e.amount ELSE 0 END AS "days_90",
            CASE WHEN days_outstanding > 90 AND days_outstanding <= 120 THEN e.amount ELSE 0 END AS "days_120",
            CASE WHEN days_outstanding > 120 THEN e.amount ELSE 0 END AS "days_120_plus",
            e.amount AS total
          FROM entries e
          LEFT JOIN customer cus ON e.customer_id = cus.id
          LEFT JOIN rev_rec_transaction con ON e.rev_rec_transaction_id = con.id
          LEFT JOIN invoice inv ON e.invoice_id = inv.id
        ),

        groups AS (
          SELECT
      """,
      makeSelectedColumns(params),
      sql"FROM bucketed",
      makeGroupByClause(params),
      sql")"
    )
  }

  private[this] def getResultColumns(params: Params): Seq[ResultColumn] = {
    params.columns.map { column =>
      ResultColumn(
        id = column,
        tpe = column match {
          case Column.Date => ColumnType.Date
          case Column.NotDue => ColumnType.Amount
          case Column.Days30 => ColumnType.Amount
          case Column.Days60 => ColumnType.Amount
          case Column.Days90 => ColumnType.Amount
          case Column.Days120 => ColumnType.Amount
          case Column.Days120Plus => ColumnType.Amount
          case Column.Total => ColumnType.Amount
          case Column.OccurredAt => ColumnType.Date
          case Column.CustomerId => ColumnType.String
          case Column.CustomerName => ColumnType.String
          case Column.CustomerEmail => ColumnType.String
          case Column.RevRecTransactionId => ColumnType.String
          case Column.RevRecTransactionTitle => ColumnType.String
          case Column.InvoiceId => ColumnType.String
          case Column.InvoiceNumber => ColumnType.String
        },
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
      .map(_.headOption.getOrElse(0L))
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

  def exportToCsv(stripeAccountId: String, liveMode: Boolean, params: Params, destinationFile: File): Future[Unit] = {
    val resultColumns = getResultColumns(params).toArray
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns.toList)

    val writer = new BufferedWriter(new FileWriter(destinationFile, StandardCharsets.UTF_8))
    writer.write(resultColumns.map { column => escapeCsv(column.id.toString) }.mkString(","))
    writer.newLine()

    db
      .stream {
        makeSql(
          makeBaseSql(stripeAccountId, liveMode, params),
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

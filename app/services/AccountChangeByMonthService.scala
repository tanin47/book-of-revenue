package services

import database.models.JournalEntry
import database.services.JournalEntryService
import database.services.JournalEntryService.{ColumnType, SortDirection}
import framework.Helpers.{escapeCsv, formatCsvValue}
import framework.{Instant, Jsonable, PeriodColumn}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import process.Helpers.generatePeriods
import slick.jdbc.{GetResult, JdbcProfile, SQLActionBuilder}

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object AccountChangeByMonthService {
  enum Column extends Enum[Column] {
    case
    RevRecTransactionId,
    RevRecTransactionTitle,
    RevRecTransactionType,
    TransactionDate,
    TransactionStatus,
    TransactionValue
  }

  case class Sort(column: Column | PeriodColumn, direction: SortDirection)
  enum GroupBy extends Enum[GroupBy] {
    case Transaction
  }

  case class Params(
    periodStart: Instant,
    periodEnd: Instant,
    currency: String,
    groupBy: GroupBy,
    accounts: Seq[JournalEntry.Account],
    customerId: String,
    sorts: Seq[Sort],
  )

  case class ResultColumn(
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

  case class Result(
    columns: Seq[ResultColumn],
    rows: Seq[Seq[Option[Any]]]
  )

  def makeGetResult(resultColumns: Seq[ResultColumn]): GetResult[Seq[Option[Any]]] = {
    GetResult[Seq[Option[Any]]] { r =>
      resultColumns.map { column =>
        val value = JournalEntryService.getValue(column.tpe, r)

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
class AccountChangeByMonthService @Inject()(
  val dbConfigProvider: DatabaseConfigProvider,
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {

  import AccountChangeByMonthService.*
  import framework.PostgresProfile.api.*

  def makeBaseSql(stripeAccountId: String, liveMode: Boolean, params: Params): SQLActionBuilder = {
    val (creditAccounts, debitAccounts) = params.accounts.partition(_.isCredit())

    def whereSql(isCredit: Boolean) = joinSqls(
      Seq(
        Some(sql"stripe_account_id = $stripeAccountId"),
        Some(sql"live_mode = $liveMode"),
        Some(sql"settlement_currency = ${params.currency}"),
        Some(sql"accounting_period >= ${params.periodStart}"),
        Some(sql"accounting_period <= ${params.periodEnd}"),
        Some(sql"customer_id = ${params.customerId}"),
        if (isCredit) {
          Some(sql"credit = ANY(${params.accounts.map(_.name)})")
        } else {
          Some(sql"debit = ANY(${params.accounts.map(_.name)})")
        }
      ).flatten,
      sql" AND "
    )

    val periodColumnSqls = joinSqls(
      generatePeriods(params.periodStart, params.periodEnd.plusMillis(1)).map { period =>
        sql"""SUM(CASE WHEN accounting_period = ${period.startedAt} THEN net_settlement_change ELSE 0 END) AS "#${PeriodColumn(period.startedAt.toEpochMilli).name}""""
      },
      sql", "
    )

    val periodColumnWhereSql = joinSqls(
      generatePeriods(params.periodStart, params.periodEnd.plusMillis(1)).map { period =>
        sql""""#${PeriodColumn(period.startedAt.toEpochMilli).name}" != 0"""
      },
      sql" OR "
    )

    makeSql(
      sql"""
        WITH
        credit_net_changes AS (
          SELECT
            *,
            credit AS account,
            (CASE
              WHEN credit = ANY(${creditAccounts.map(_.name)}) THEN settlement_amount
              ELSE -settlement_amount
            END) AS net_settlement_change
          FROM journal_entry
          WHERE
      """,
      whereSql(isCredit = true),
      sql"""
        ),

        debit_net_changes AS (
          SELECT
            *,
            debit AS account,
            (CASE
              WHEN debit = ANY(${debitAccounts.map(_.name)}) THEN settlement_amount
              ELSE -settlement_amount
            END) AS net_settlement_change
          FROM journal_entry
          WHERE
      """,
      whereSql(isCredit = false),
      sql"""
        ),

        net_changes AS (
          SELECT * FROM credit_net_changes UNION ALL SELECT * FROM debit_net_changes
        ),

        unfiltered_groups AS (
          SELECT
            rev_rec_transaction_id,
      """,
      periodColumnSqls,
      sql"""
          FROM net_changes
          GROUP BY rev_rec_transaction_id
        ),

        filtered_groups AS (
          SELECT * FROM unfiltered_groups WHERE
      """,
      periodColumnWhereSql,
      sql"""
        ),

        groups AS (
          SELECT
            t.id AS transaction_id,
            t.type AS transaction_type,
            t.title AS transaction_title,
            t.started_at AS transaction_date,
            t.status AS transaction_status,
            t.settlement_total_value AS transaction_value,
            g.*
          FROM rev_rec_transaction t
          LEFT JOIN filtered_groups g ON t.id = g.rev_rec_transaction_id
          WHERE t.customer_id = ${params.customerId}
        )
      """
    )
  }

  def count(
    stripeAccountId: String,
    liveMode: Boolean,
    params: Params
  ): Future[Long] = {
    db.run {
      makeSql(
        makeBaseSql(stripeAccountId, liveMode, params),
        sql"""
        SELECT COUNT(*) FROM groups
      """
      ).as[Long]
        .map(_.headOption.getOrElse(0L))
    }
  }

  private[this] def makeOrderByClause(sorts: Seq[Sort]): SQLActionBuilder = {
    if (sorts.isEmpty) {
      return sql"transaction_date DESC"
    }

    val sortClauses = sorts.map { sort =>
      val name = sort.column match {
        case e: PeriodColumn => e.name
        case Column.RevRecTransactionId => "transaction_id"
        case Column.RevRecTransactionType => "transaction_type"
        case Column.RevRecTransactionTitle => "transaction_title"
        case Column.TransactionValue => "transaction_value"
        case Column.TransactionStatus => "transaction_status"
        case Column.TransactionDate => "transaction_date"
      }

      sql""""#$name" #${sort.direction.toString.toUpperCase} NULLS LAST"""
    }

    joinSqls(sortClauses, sql", ")
  }

  private[this] def getResultColumns(params: Params): Seq[ResultColumn] = {
    val periods = generatePeriods(params.periodStart, params.periodEnd.plusMillis(1))

    val columns = (
      Seq(
        Column.RevRecTransactionId,
        Column.RevRecTransactionTitle,
        Column.TransactionValue,
        Column.RevRecTransactionType,
        Column.TransactionStatus,
        Column.TransactionDate,
      ) ++ periods.map { period =>
        PeriodColumn(period.startedAt.toEpochMilli)
      }
    ).asInstanceOf[Seq[Column | PeriodColumn]]

    columns.map { column =>
      ResultColumn(
        id = column,
        tpe = column match {
          case _: PeriodColumn => ColumnType.DeltaAmount
          case Column.RevRecTransactionId => ColumnType.String
          case Column.RevRecTransactionTitle => ColumnType.String
          case Column.RevRecTransactionType => ColumnType.String
          case Column.TransactionValue => ColumnType.Amount
          case Column.TransactionStatus => ColumnType.String
          case Column.TransactionDate => ColumnType.Timestamp
        }
      )
    }
  }

  private[this] def makeSelectedColumns(resultColumns: Seq[ResultColumn]): SQLActionBuilder = {
    joinSqls(
      resultColumns.map { col =>
        col.id match {
          case p: PeriodColumn => sql""""#${p.name}""""
          case Column.RevRecTransactionId => sql"transaction_id"
          case Column.RevRecTransactionTitle => sql"transaction_title"
          case Column.RevRecTransactionType => sql"transaction_type"
          case Column.TransactionValue => sql"transaction_value"
          case Column.TransactionStatus => sql"transaction_status"
          case Column.TransactionDate => sql"transaction_date"
        }
      },
      sql", "
    )
  }

  private def makeListSql(stripeAccountId: String, liveMode: Boolean, params: Params, resultColumns: Seq[ResultColumn]): SQLActionBuilder = {
    makeSql(
      makeBaseSql(stripeAccountId, liveMode, params),
      sql"""
        SELECT
      """,
      makeSelectedColumns(resultColumns),
      sql"""
      FROM groups
        ORDER BY
      """,
      makeOrderByClause(params.sorts),
    )
  }

  def get(
    stripeAccountId: String,
    liveMode: Boolean,
    params: Params,
    offset: Int,
    limit: Int
  ): Future[Result] = {
    val resultColumns = getResultColumns(params)
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns)

    db.run {
      makeSql(
        makeListSql(stripeAccountId, liveMode, params, resultColumns),
        sql"""
            LIMIT $limit OFFSET $offset
          """
      ).as[Seq[Option[Any]]]
    }.map { rows =>
      Result(resultColumns, rows.toList)
    }
  }

  def exportToCsv(stripeAccountId: String, liveMode: Boolean, params: Params): Future[File] = {
    val destinationFile = Files.createTempFile("transaction", ".csv").toFile
    val resultColumns = getResultColumns(params).toArray
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
      .map { _ => destinationFile }
  }
}

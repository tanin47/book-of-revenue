package services

import database.models.JournalEntry
import database.models.JournalEntry.AccountCategory
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
import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

object MonthlyGrrService {
  case class DataPoint(
    period: Instant,
    value: Double
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "period" -> period.toEpochMilli,
      "value" -> value
    )
  }

  enum Column extends Enum[Column] {
    case
    CustomerEmail,
    CustomerId,
    CustomerName
  }
  case class Sort(column: Column, direction: SortDirection)

  case class CustomerRevenueByMonthSort(
    column: Column | PeriodColumn,
    direction: SortDirection
  )
  case class CustomerRevenueByMonthParams(
    keyword: String,
    periodStart: Instant,
    periodEnd: Instant,
    currency: String,
    sorts: Seq[CustomerRevenueByMonthSort]
  )
  case class CustomerRevenueByMonthResultColumn(
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
  case class CustomerRevenueByMonthResult(
    columns: Seq[CustomerRevenueByMonthResultColumn],
    rows: Seq[Seq[Option[Any]]]
  )
  def makeGetResultForCustomerRevenueByMonth(resultColumns: Seq[CustomerRevenueByMonthResultColumn]): GetResult[Seq[Option[Any]]] = {
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
class MonthlyGrrService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import MonthlyGrrService.*
  import framework.PostgresProfile.api.*

  def makeBaseSql(stripeAccountId: String, liveMode: Boolean, currency: String, periodStart: Instant, periodEnd: Instant): SQLActionBuilder = {
    val revenueAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.Revenue).toList
    val contraRevenueAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.ContraRevenue).toList

    sql"""
      WITH raw_entries AS (
        SELECT
          accounting_period AT TIME ZONE 'UTC' AS accounting_period,
          customer_id,
          SUM(
            (CASE WHEN debit = ANY(${(revenueAccounts ++ contraRevenueAccounts).map(_.name)}) THEN -settlement_amount ELSE 0 END)
            + (CASE WHEN credit = ANY(${(revenueAccounts ++ contraRevenueAccounts).map(_.name)}) THEN settlement_amount ELSE 0 END)
          ) AS net_revenue
        FROM journal_entry
        WHERE
          stripe_account_id = $stripeAccountId
          AND live_mode = $liveMode
          AND accounting_period >= ($periodStart - INTERVAL '1 month')
          AND accounting_period <= $periodEnd
          AND settlement_currency = $currency
        GROUP BY accounting_period, customer_id
      ),

      customer_entries AS (
        SELECT
          COALESCE(e.accounting_period, b.accounting_period + INTERVAL '1 month') AS accounting_period,
          COALESCE(e.customer_id, b.customer_id) AS customer_id,
          (CASE
            WHEN e.net_revenue < 0 OR b.net_revenue <= 0 THEN 0
            WHEN COALESCE(e.net_revenue, 0) < b.net_revenue THEN COALESCE(e.net_revenue, 0) * 100 / b.net_revenue
            ELSE 100
          END) AS grr
        FROM raw_entries e
        RIGHT JOIN raw_entries b
        ON e.accounting_period = (b.accounting_period + INTERVAL '1 month')
        AND e.customer_id = b.customer_id
        WHERE b.net_revenue > 0
      )
    """
  }


  def get(stripeAccountId: String, liveMode: Boolean, currency: String, periodStart: Instant, periodEnd: Instant): Future[Seq[DataPoint]] = {
    db.run {
        makeSql(
          makeBaseSql(stripeAccountId, liveMode, currency, periodStart, periodEnd),
          sql"""
          SELECT
            accounting_period,
            SUM(grr) / COUNT(customer_id) AS grr
          FROM customer_entries
          GROUP BY accounting_period
          ORDER BY accounting_period ASC
        """
        ).as[(Instant, Double)]
      }
      .map { items =>
        items.map { case (period, value) =>
          DataPoint(period = period, value = value)
        }
      }
      .map { items =>
        val pointByPeriod = items.groupBy(_.period).view.mapValues(_.head).toMap

        generatePeriods(periodStart, periodEnd.plusMillis(1)).map { period =>
          pointByPeriod.getOrElse(period.startedAt, DataPoint(period = period.startedAt, value = 0))
        }
      }
  }


  def makeBaseCustomerRevenueByMonthWithSql(stripeAccountId: String, liveMode: Boolean, params: CustomerRevenueByMonthParams): SQLActionBuilder = {
    val periods = generatePeriods(params.periodStart, params.periodEnd.plusMillis(1))
    val sumPeriodColumnsSql = joinSqls(
      periods.map { period =>
        sql"""
          SUM(CASE WHEN accounting_period = ${period.startedAt} AT TIME ZONE 'UTC' THEN grr ELSE 0 END) AS "#${PeriodColumn(period.startedAt.toEpochMilli).name}"
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

    makeSql(
      makeBaseSql(stripeAccountId, liveMode, params.currency, params.periodStart, params.periodEnd),
      sql"""
        ,
        month_customer_entries AS (
          SELECT
            customer_id,
      """,
      sumPeriodColumnsSql,
      sql"""
          FROM customer_entries
          GROUP BY customer_id
        ),

        customer_entry_with_infos AS (
          SELECT
            COALESCE(e.customer_id, c.id) AS customer_id,
            c.name AS customer_name,
            c.email AS customer_email,
      """,
      periodColumnsSql,
      sql"""
          FROM
            customer c
            LEFT JOIN month_customer_entries e
            ON c.id = e.customer_id
          WHERE c.stripe_account_id = $stripeAccountId AND c.live_mode = $liveMode
      """,
      keywordCond,
      sql"""
        )
      """
    )
  }

  def countCustomerRevenueByMonth(
    stripeAccountId: String,
    liveMode: Boolean,
    params: CustomerRevenueByMonthParams
  ): Future[Long] = {
    db
      .run {
        makeSql(
          makeBaseCustomerRevenueByMonthWithSql(stripeAccountId, liveMode, params),
          sql"""
            SELECT COUNT(*) FROM customer_entry_with_infos
          """,
        ).as[Long]
      }
      .map(_.headOption.getOrElse(0L))
  }

  private[this] def getCustomerRevenueByMonthResultColumns(params: CustomerRevenueByMonthParams): Seq[CustomerRevenueByMonthResultColumn] = {
    val periods = generatePeriods(params.periodStart, params.periodEnd.plusMillis(1))
    Seq(
      CustomerRevenueByMonthResultColumn(id = Column.CustomerId, tpe = ColumnType.String),
      CustomerRevenueByMonthResultColumn(id = Column.CustomerName, tpe = ColumnType.String),
      CustomerRevenueByMonthResultColumn(id = Column.CustomerEmail, tpe = ColumnType.String),
    ) ++ periods.map { period =>
      CustomerRevenueByMonthResultColumn(id = PeriodColumn(period.startedAt.toEpochMilli), tpe = ColumnType.Percentage)
    }
  }

  private[this] def makeCustomerOrderByClause(sorts: Seq[CustomerRevenueByMonthSort], periodEnd: Instant): SQLActionBuilder = {
    if (sorts.isEmpty) {
      return sql""""#${PeriodColumn(periodEnd.toEpochMilli).name}" DESC NULLS LAST, customer_name ASC"""
    }

    joinSqls(
      sorts.map { sort =>
        sort.column match {
          case p: PeriodColumn => sql""""#${p.name}" #${sort.direction.toString.toUpperCase} NULLS LAST"""
          case c: Column =>
            val columnName = c match {
              case Column.CustomerId => "customer_id"
              case Column.CustomerName => "customer_name"
              case Column.CustomerEmail => "customer_email"
            }
            sql"#$columnName #${sort.direction.toString.toUpperCase} NULLS LAST"
        }
      },
      sql","
    )
  }

  private[this] def makeCustomerByMonthSql(stripeAccountId: String, liveMode: Boolean, params: CustomerRevenueByMonthParams): SQLActionBuilder = {
    makeSql(
      makeBaseCustomerRevenueByMonthWithSql(stripeAccountId, liveMode, params),
      sql"""
        SELECT * FROM customer_entry_with_infos ORDER BY
      """,
      makeCustomerOrderByClause(params.sorts, params.periodEnd),
    )
  }

  def getCustomerRevenueByMonth(
    stripeAccountId: String,
    liveMode: Boolean,
    params: CustomerRevenueByMonthParams,
    offset: Int,
    limit: Int
  ): Future[CustomerRevenueByMonthResult] = {
    val resultColumns = getCustomerRevenueByMonthResultColumns(params)
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResultForCustomerRevenueByMonth(resultColumns)
    db
      .run {
        makeSql(
          makeCustomerByMonthSql(stripeAccountId, liveMode, params),
          sql"LIMIT $limit OFFSET $offset"
        ).as[Seq[Option[Any]]]
      }
      .map { rows =>
        CustomerRevenueByMonthResult(resultColumns, rows.toList)
      }
  }

  def exportCustomerRevenueByMonthToCsv(stripeAccountId: String, liveMode: Boolean, params: CustomerRevenueByMonthParams): Future[File] = {
    val resultColumns = getCustomerRevenueByMonthResultColumns(params).toArray
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResultForCustomerRevenueByMonth(resultColumns.toList)

    val destinationFile = Files.createTempFile("customer-nrr", ".csv").toFile

    val writer = new BufferedWriter(new FileWriter(destinationFile, StandardCharsets.UTF_8))
    writer.write(resultColumns.map { column => escapeCsv(column.id.toString) }.mkString(","))
    writer.newLine()

    db
      .stream {
        makeCustomerByMonthSql(stripeAccountId, liveMode, params).as[Seq[Option[Any]]]
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

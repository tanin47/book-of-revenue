package services

import database.models.JournalEntry
import database.services.JournalEntryService
import database.services.JournalEntryService.{ColumnType, SortDirection}
import framework.Helpers.{escapeCsv, formatCsvValue}
import framework.{Instant, Jsonable, PeriodColumn}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import process.Helpers.generatePeriods
import services.ContractualLiabilityService.GroupBy
import slick.jdbc.{GetResult, JdbcProfile, SQLActionBuilder}

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

object ContractualLiabilityService {
  case class DataPoint(
    period: Instant,
    value: Long
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "period" -> period.toEpochMilli,
      "value" -> value
    )
  }

  case class ChangeDataPointAmount(
    event: String,
    value: Long
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "id" -> event,
      "value" -> value
    )
  }

  case class ChangeDataPoint(
    period: Instant,
    values: Seq[ChangeDataPointAmount]
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "period" -> period.toEpochMilli,
      "values" -> values.map(_.toJson())
    )
  }


  enum Column extends Enum[Column] {
    case
    CustomerEmail,
    CustomerId,
    CustomerName,
    RevRecTransactionId,
    RevRecTransactionTitle,
    RevRecTransactionType,
    TransactionDate,
    TransactionStatus,
    TransactionValue,
    ProductId,
    ProductName
  }
  case class Sort(column: Column, direction: SortDirection)
  case class ByMonthSort(
    column: Column | PeriodColumn,
    direction: SortDirection
  )

  enum GroupBy extends Enum[GroupBy] {
    case Product, Customer, Transaction
  }

  case class ByMonthParams(
    keyword: String,
    periodStart: Instant,
    periodEnd: Instant,
    currency: String,
    groupBy: GroupBy,
    customerId: Option[String],
    sorts: Seq[ByMonthSort],
  )
  case class ByMonthResultColumn(
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
  case class ByMonthResult(
    columns: Seq[ByMonthResultColumn],
    rows: Seq[Seq[Option[Any]]]
  )
  def makeGetResultForByMonth(resultColumns: Seq[ByMonthResultColumn]): GetResult[Seq[Option[Any]]] = {
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
class ContractualLiabilityService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  balanceSheetService: BalanceSheetService
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import ContractualLiabilityService.*
  import framework.PostgresProfile.api.*

  def get(
    stripeAccountId: String,
    liveMode: Boolean,
    contractualLiabilityAccounts: Seq[JournalEntry.Account],
    periodStart: Instant,
    periodEnd: Instant,
    currency: String
  ): Future[Seq[DataPoint]] = {
    balanceSheetService.get(
      stripeAccountId,
      liveMode,
      BalanceSheetService.Params(
        periodStart = Some(periodStart),
        periodEnd = Some(periodEnd),
        groupBy = Some(BalanceSheetService.GroupBy.Summary),
        groupBy2 = None,
        currency = currency,
        showOnly = None,
        productId = None,
        customerId = None,
        transactionId = None,
        accounts = contractualLiabilityAccounts.map(_.name),
        columns = Seq(
          BalanceSheetService.Column.AccountingPeriod,
          BalanceSheetService.Column.Category,
          BalanceSheetService.Column.Account,
          BalanceSheetService.Column.NetChange,
          BalanceSheetService.Column.EndingBalance,
        ),
        sorts = Seq(BalanceSheetService.Sort(BalanceSheetService.Column.AccountingPeriod, SortDirection.Asc))
      ),
      0,
      100000
    )
      .map { result =>
        val periods = generatePeriods(periodStart, periodEnd.plusMillis(1))
        val rowByPeriod = result.rows.groupBy(_.head.asInstanceOf[Option[Long]].get).view.mapValues { vs =>
          vs.map { v => v.apply(4).asInstanceOf[Option[Long]].getOrElse(0L) }.sum
        }.toMap

        periods.map { period =>
          DataPoint(
            period = period.startedAt,
            value = rowByPeriod.getOrElse(period.startedAt.toEpochMilli, 0L)
          )
        }
      }
  }

  def getChange(
    stripeAccountId: String,
    liveMode: Boolean,
    contractualLiabilityAccounts: Seq[JournalEntry.Account],
    periodStart: Instant,
    periodEnd: Instant,
    currency: String
  ): Future[Seq[ChangeDataPoint]] = {
    balanceSheetService.get(
        stripeAccountId,
        liveMode,
        BalanceSheetService.Params(
          periodStart = Some(periodStart),
          periodEnd = Some(periodEnd),
          groupBy = Some(BalanceSheetService.GroupBy.Summary),
          groupBy2 = Some(BalanceSheetService.GroupBy2.Event),
          currency = currency,
          showOnly = None,
          productId = None,
          customerId = None,
          transactionId = None,
          accounts = contractualLiabilityAccounts.map(_.name),
          columns = Seq(
            BalanceSheetService.Column.AccountingPeriod,
            BalanceSheetService.Column.Category,
            BalanceSheetService.Column.Account,
            BalanceSheetService.Column.Event,
            BalanceSheetService.Column.NetChange,
          ),
          sorts = Seq.empty
        ),
        0,
        100000
      )
      .map { result =>
        val changesByPeriod = result.rows.groupBy(_.head.asInstanceOf[Option[Long]].get).view.mapValues { vs =>
          vs.groupBy(_.apply(3).asInstanceOf[Option[String]].get).view.mapValues { vs2 =>
            vs2.map { v => v.apply(4).asInstanceOf[Option[Long]].getOrElse(0L) }.sum
          }.toList.sortBy(_._1)
        }.toMap

        generatePeriods(periodStart, periodEnd.plusMillis(1)).map { period =>
          ChangeDataPoint(
            period = period.startedAt,
            values = changesByPeriod.getOrElse(period.startedAt.toEpochMilli, Seq.empty).map { entry =>
              ChangeDataPointAmount(
                event = entry._1,
                value = entry._2
              )
            }
          )
        }
      }
  }

  def makeBaseEndingBalanceByMonthWithSql(
    stripeAccountId: String,
    liveMode: Boolean,
    params: ByMonthParams,
    accounts: Seq[JournalEntry.Account]
  ): SQLActionBuilder = {
    val periods = generatePeriods(params.periodStart, params.periodEnd.plusMillis(1))
    val sumPeriodColumnsSql = joinSqls(
      periods.map { period =>
        sql"""
          SUM(CASE WHEN accounting_period = ${period.startedAt} THEN settlement_ending_balance ELSE 0 END) AS "#${PeriodColumn(period.startedAt.toEpochMilli).name}"
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

    val baseSql = balanceSheetService.makeBaseWithSql(
      stripeAccountId = stripeAccountId,
      liveMode = liveMode,
      params = BalanceSheetService.Params(
        periodStart = Some(params.periodStart),
        periodEnd = Some(params.periodEnd),
        groupBy = Some(params.groupBy match {
          case GroupBy.Product => BalanceSheetService.GroupBy.Product
          case GroupBy.Customer => BalanceSheetService.GroupBy.Customer
          case GroupBy.Transaction => BalanceSheetService.GroupBy.Transaction
        }),
        groupBy2 = None,
        currency = params.currency,
        showOnly = None,
        productId = None,
        customerId = None,
        transactionId = None,
        accounts = accounts.map(_.name),
        columns = Seq(
          BalanceSheetService.Column.AccountingPeriod,
          BalanceSheetService.Column.CustomerId,
          BalanceSheetService.Column.RevRecTransactionId,
          BalanceSheetService.Column.ProductId,
          BalanceSheetService.Column.EndingBalance,
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
            revenue_by_month_entries AS (
              SELECT
                product_id,
          """,
          sumPeriodColumnsSql,
          sql"""
              FROM groups
              GROUP BY product_id
            ),

            revenue_by_month_entry_with_infos AS (
              SELECT
                COALESCE(e.product_id, p.id) AS product_id,
                p.name AS product_name,
          """,
          periodColumnsSql,
          sql"""
              FROM
                revenue_by_month_entries e
                LEFT JOIN product p
                ON p.id = e.product_id
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
            revenue_by_month_entries AS (
              SELECT
                customer_id,
          """,
          sumPeriodColumnsSql,
          sql"""
              FROM groups
              GROUP BY customer_id
            ),

            revenue_by_month_entry_with_infos AS (
              SELECT
                COALESCE(e.customer_id, c.id) AS customer_id,
                c.name AS customer_name,
                c.email AS customer_email,
          """,
          periodColumnsSql,
          sql"""
              FROM
                customer c
                LEFT JOIN revenue_by_month_entries e
                ON c.id = e.customer_id
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
            revenue_by_month_entries AS (
              SELECT
                rev_rec_transaction_id,
          """,
          sumPeriodColumnsSql,
          sql"""
              FROM groups
              GROUP BY rev_rec_transaction_id
            ),

            revenue_by_month_entry_with_infos AS (
              SELECT
                COALESCE(e.rev_rec_transaction_id, c.id) AS rev_rec_transaction_id,
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
                LEFT JOIN revenue_by_month_entries e
                ON c.id = e.rev_rec_transaction_id
              WHERE c.stripe_account_id = $stripeAccountId AND c.live_mode = $liveMode AND
          """,
          customerCond,
          sql"""
            )
          """
        )
    }
  }

  def countEndingBalanceByMonth(
    stripeAccountId: String,
    liveMode: Boolean,
    params: ByMonthParams,
    accounts: Seq[JournalEntry.Account]
  ): Future[Long] = {
    db
      .run {
        makeSql(
          makeBaseEndingBalanceByMonthWithSql(stripeAccountId, liveMode, params, accounts),
          sql"""
            SELECT COUNT(*) FROM revenue_by_month_entry_with_infos
          """,
        ).as[Long]
      }
      .map(_.headOption.getOrElse(0L))
  }

  private[this] def getEndingBalanceByMonthResultColumns(params: ByMonthParams): Seq[ByMonthResultColumn] = {
    val baseColumns = params.groupBy match {
      case GroupBy.Product =>
        Seq(
          ByMonthResultColumn(id = Column.ProductId, tpe = ColumnType.String),
          ByMonthResultColumn(id = Column.ProductName, tpe = ColumnType.String),
        )
      case GroupBy.Customer =>
        Seq(
          ByMonthResultColumn(id = Column.CustomerId, tpe = ColumnType.String),
          ByMonthResultColumn(id = Column.CustomerName, tpe = ColumnType.String),
          ByMonthResultColumn(id = Column.CustomerEmail, tpe = ColumnType.String),
        )
      case GroupBy.Transaction =>
        Seq(
          ByMonthResultColumn(id = Column.RevRecTransactionId, tpe = ColumnType.String),
          ByMonthResultColumn(id = Column.RevRecTransactionTitle, tpe = ColumnType.String),
          ByMonthResultColumn(id = Column.TransactionValue, tpe = ColumnType.Amount),
          ByMonthResultColumn(id = Column.RevRecTransactionType, tpe = ColumnType.String),
          ByMonthResultColumn(id = Column.TransactionStatus, tpe = ColumnType.String),
          ByMonthResultColumn(id = Column.TransactionDate, tpe = ColumnType.Timestamp),
        )
    }
    val periods = generatePeriods(params.periodStart, params.periodEnd.plusMillis(1))

    baseColumns ++ periods.map { period =>
      ByMonthResultColumn(id = PeriodColumn(period.startedAt.toEpochMilli), tpe = ColumnType.Amount)
    }
  }

  private[this] def makeRevenueByMonthOrderByClause(sorts: Seq[ByMonthSort], periodEnd: Instant, groupBy: GroupBy): SQLActionBuilder = {
    if (sorts.isEmpty) {
      groupBy match {
        case GroupBy.Product => return sql""""#${PeriodColumn(periodEnd.toEpochMilli).name}" DESC NULLS LAST, product_name ASC"""
        case GroupBy.Customer => return sql""""#${PeriodColumn(periodEnd.toEpochMilli).name}" DESC NULLS LAST, customer_name ASC"""
        case GroupBy.Transaction => return sql"""transaction_started_at DESC NULLS LAST, transaction_settlement_total_value DESC"""
      }
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
              case Column.RevRecTransactionId => "rev_rec_transaction_id"
              case Column.RevRecTransactionTitle => "rev_rec_transaction_title"
              case Column.RevRecTransactionType => "rev_rec_transaction_type"
              case Column.TransactionDate => "transaction_started_at"
              case Column.TransactionValue => "transaction_settlement_total_value"
              case Column.TransactionStatus => "transaction_status"
              case Column.ProductId => "product_id"
              case Column.ProductName => "product_name"
            }
            sql"#$columnName #${sort.direction.toString.toUpperCase} NULLS LAST"
        }
      },
      sql","
    )
  }

  private[this] def makeEndingBalanceByMonthSql(
    stripeAccountId: String,
    liveMode: Boolean,
    params: ByMonthParams,
    accounts: Seq[JournalEntry.Account]
  ): SQLActionBuilder = {
    makeSql(
      makeBaseEndingBalanceByMonthWithSql(stripeAccountId, liveMode, params, accounts),
      sql"""
        SELECT * FROM revenue_by_month_entry_with_infos ORDER BY
      """,
      makeRevenueByMonthOrderByClause(params.sorts, params.periodEnd, params.groupBy),
    )
  }

  def getEndingBalanceByMonth(
    stripeAccountId: String,
    liveMode: Boolean,
    params: ByMonthParams,
    accounts: Seq[JournalEntry.Account],
    offset: Int,
    limit: Int
  ): Future[ByMonthResult] = {
    val resultColumns = getEndingBalanceByMonthResultColumns(params)
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResultForByMonth(resultColumns)
    db
      .run {
        makeSql(
          makeEndingBalanceByMonthSql(stripeAccountId, liveMode, params, accounts),
          sql"LIMIT $limit OFFSET $offset"
        ).as[Seq[Option[Any]]]
      }
      .map { rows =>
        ByMonthResult(resultColumns, rows.toList)
      }
  }

  def exportEndingBalanceByMonthToCsv(
    stripeAccountId: String,
    liveMode: Boolean,
    params: ByMonthParams,
    accounts: Seq[JournalEntry.Account],
  ): Future[File] = {
    val resultColumns = getEndingBalanceByMonthResultColumns(params).toArray
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResultForByMonth(resultColumns.toList)

    val destinationFile = Files.createTempFile(s"${params.groupBy.name.toLowerCase}-contractual-liabilities", ".csv").toFile

    val writer = new BufferedWriter(new FileWriter(destinationFile, StandardCharsets.UTF_8))
    writer.write(resultColumns.map { column => escapeCsv(column.id.toString) }.mkString(","))
    writer.newLine()

    db
      .stream {
        makeEndingBalanceByMonthSql(stripeAccountId, liveMode, params, accounts).as[Seq[Option[Any]]]
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

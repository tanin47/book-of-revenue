package services

import database.models.JournalEntry
import database.services.JournalEntryService
import database.services.JournalEntryService.{ColumnType, SortDirection}
import database.services.MetronomeLineItemService.getMetronomeProductNames
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
import scala.language.implicitConversions
import org.jooq.impl.DSL
import org.jooq.impl.DSL.*
import org.jooq.scalaextensions.Conversions.*
import framework.Jooq.*
import jooq.generated.public.Tables.TRANSACTION
import jooq.generated.stripe.Tables.{STRIPE_CUSTOMER, STRIPE_INVOICE, STRIPE_INVOICE_LINE_ITEM, STRIPE_PRODUCT}
import org.jooq.{CommonTableExpression, Condition, Field, SortField, SortOrder}

import scala.jdk.CollectionConverters.SeqHasAsJava

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
    TransactionId,
    TransactionTitle,
    TransactionType,
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
  ): CommonTableExpression[?] = {
    val periods = generatePeriods(params.periodStart, params.periodEnd.plusMillis(1))
    val sumPeriodColumns = periods.map { period =>
      sum(
        when(field("accounting_period") === period.startedAt, field("settlement_ending_balance", classOf[java.lang.Long])).otherwise(0L)
      ).as(PeriodColumn(period.startedAt.toEpochMilli).name)
    }

    val periodColumns = joinSqls(
      periods.map { period => sql""""#${PeriodColumn(period.startedAt.toEpochMilli).name}"""" },
      sql", "
    )

    val keywordCond = if (params.keyword.isEmpty) {
      Seq.empty
    } else {
      val modifiedKeyword = s"%${params.keyword}%"
      Seq(or(
        field("customer_id").likeIgnoreCase(modifiedKeyword),
        field("name").likeIgnoreCase(modifiedKeyword),
        field("email").likeIgnoreCase(modifiedKeyword),
      ))
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
          BalanceSheetService.Column.TransactionId,
          BalanceSheetService.Column.ProductId,
          BalanceSheetService.Column.EndingBalance,
        ),
        sorts = Seq.empty
      )
    )

    params.groupBy match {
      case GroupBy.Product =>
        val revenueByMonthEntries = name("revenue_by_month_entries").as(
          `with`(baseSql)
            .select((
              Seq(field("product_id")) ++ sumPeriodColumns
            ).asJava)
            .from(baseSql)
            .groupBy(field("product_id"))
        )
        val metronomeProductNames = getMetronomeProductNames()

        name("revenue_by_month_entry_with_infos").as(
          `with`(revenueByMonthEntries)
            .select(
              revenueByMonthEntries.asterisk(),
              coalesce(metronomeProductNames.field("name"), STRIPE_PRODUCT.NAME).as("product_name")
            )
            .from(revenueByMonthEntries)
            .leftJoin(STRIPE_PRODUCT).on(revenueByMonthEntries.field("product_id", classOf[Any]) === STRIPE_PRODUCT.ID)
            .leftJoin(metronomeProductNames).on(revenueByMonthEntries.field("product_id", classOf[Any]) === metronomeProductNames.field("id"))
            .where(keywordCond.asJava)
        )
      case GroupBy.Customer =>
        val revenueByMonthEntries = name("revenue_by_month_entries").as(
          `with`(baseSql)
            .select((
              Seq(field("customer_id")) ++ sumPeriodColumns
            ).asJava)
            .from(baseSql)
            .groupBy(field("customer_id"))
        )

        name("revenue_by_month_entry_with_infos").as(
          `with`(revenueByMonthEntries)
            .select(
              revenueByMonthEntries.asterisk().except("customer_id"),
              coalesce(STRIPE_CUSTOMER.ID, revenueByMonthEntries.field("customer_id")).as("customer_id"),
              STRIPE_CUSTOMER.NAME.as("customer_name"),
              STRIPE_CUSTOMER.EMAIL.as("customer_email")
            )
            .from(STRIPE_CUSTOMER)
            .fullOuterJoin(revenueByMonthEntries)
            .on(revenueByMonthEntries.field("customer_id", classOf[Any]) === STRIPE_CUSTOMER.ID)
            .where((
              Seq(STRIPE_CUSTOMER.STRIPE_ACCOUNT_ID === stripeAccountId, STRIPE_CUSTOMER.LIVE_MODE === liveMode) ++ keywordCond
            ).asJava)
        )
      case GroupBy.Transaction =>
        val customerCond = params.customerId match {
          case None => sql"customer_id IS NULL"
          case Some(customerId) => sql"customer_id = $customerId"
        }

        val revenueByMonthEntries = name("revenue_by_month_entries").as(
          `with`(baseSql)
            .select((Seq(field("transaction_id")) ++ sumPeriodColumns).asJava)
            .from(baseSql)
            .groupBy(field("transaction_id"))
        )

        name("revenue_by_month_entry_with_infos").as(
          `with`(revenueByMonthEntries)
            .select(
              TRANSACTION.ID.as("transaction_id"),
              TRANSACTION.TITLE.as("transaction_title"),
              TRANSACTION.SETTLEMENT_TOTAL_VALUE.as("transaction_settlement_total_value"),
              TRANSACTION.TYPE.as("transaction_type"),
              TRANSACTION.STATUS.as("transaction_status"),
              TRANSACTION.STARTED_AT.as("transaction_started_at"),
              revenueByMonthEntries.asterisk().except("transaction_id")
            )
            .from(TRANSACTION)
            .leftJoin(revenueByMonthEntries)
            .on(revenueByMonthEntries.field("transaction_id", classOf[Any]) === TRANSACTION.ID)
            .where(
              TRANSACTION.STRIPE_ACCOUNT_ID === stripeAccountId,
              TRANSACTION.LIVE_MODE === liveMode,
              params.customerId match {
                case None => TRANSACTION.CUSTOMER_ID.isNull()
                case Some(customerId) => TRANSACTION.CUSTOMER_ID === customerId
              }
            )
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
        val result = makeBaseEndingBalanceByMonthWithSql(stripeAccountId, liveMode, params, accounts)
        toSqlActionBuilder(
          select(DSL.count(asterisk())).from(result)
        ).as[Long]
      }
      .map(_.headOption.getOrElse(0L))
  }

  private def getEndingBalanceByMonthResultColumns(params: ByMonthParams): Seq[ByMonthResultColumn] = {
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
          ByMonthResultColumn(id = Column.TransactionId, tpe = ColumnType.String),
          ByMonthResultColumn(id = Column.TransactionTitle, tpe = ColumnType.String),
          ByMonthResultColumn(id = Column.TransactionValue, tpe = ColumnType.Amount),
          ByMonthResultColumn(id = Column.TransactionType, tpe = ColumnType.String),
          ByMonthResultColumn(id = Column.TransactionStatus, tpe = ColumnType.String),
          ByMonthResultColumn(id = Column.TransactionDate, tpe = ColumnType.Timestamp),
        )
    }
    val periods = generatePeriods(params.periodStart, params.periodEnd.plusMillis(1))

    baseColumns ++ periods.map { period =>
      ByMonthResultColumn(id = PeriodColumn(period.startedAt.toEpochMilli), tpe = ColumnType.Amount)
    }
  }

  private def makeRevenueByMonthOrderByClause(sorts: Seq[ByMonthSort], periodEnd: Instant, groupBy: GroupBy): Seq[SortField[?]] = {
    if (sorts.isEmpty) {
      groupBy match {
        case GroupBy.Product => return Seq(field(quotedName(PeriodColumn(periodEnd.toEpochMilli).name)).desc().nullsLast(), field("product_name").asc())
        case GroupBy.Customer => return Seq(field(quotedName(PeriodColumn(periodEnd.toEpochMilli).name)).desc().nullsLast(), field("customer_name").asc())
        case GroupBy.Transaction => return Seq(field("transaction_started_at").desc().nullsLast(), field("transaction_settlement_total_value").desc())
      }
    }

    sorts.map { sort =>
      sort.column match {
        case p: PeriodColumn => field(quotedName(p.name)).sort(SortOrder.valueOf(sort.direction.toString.toUpperCase)).nullsLast()
        case c: Column =>
          val columnName = c match {
            case Column.CustomerId => "customer_id"
            case Column.CustomerName => "customer_name"
            case Column.CustomerEmail => "customer_email"
            case Column.TransactionId => "transaction_id"
            case Column.TransactionTitle => "transaction_title"
            case Column.TransactionType => "transaction_type"
            case Column.TransactionDate => "transaction_started_at"
            case Column.TransactionValue => "transaction_settlement_total_value"
            case Column.TransactionStatus => "transaction_status"
            case Column.ProductId => "product_id"
            case Column.ProductName => "product_name"
          }

          field(columnName).sort(SortOrder.valueOf(sort.direction.toString.toUpperCase)).nullsLast()
      }
    }
  }

  private def makeEndingBalanceByMonthSql(
    stripeAccountId: String,
    liveMode: Boolean,
    params: ByMonthParams,
    accounts: Seq[JournalEntry.Account]
  ): CommonTableExpression[?] = {
    val result = makeBaseEndingBalanceByMonthWithSql(stripeAccountId, liveMode, params, accounts)

    name("items").as(
      `with`(result)
        .select(asterisk())
        .from(result)
        .orderBy(makeRevenueByMonthOrderByClause(params.sorts, params.periodEnd, params.groupBy).asJava)
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
        val items = makeEndingBalanceByMonthSql(stripeAccountId, liveMode, params, accounts)
        toSqlActionBuilder(
          select(asterisk()).from(items).limit(limit).offset(offset)
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
        val items = makeEndingBalanceByMonthSql(stripeAccountId, liveMode, params, accounts)
        toSqlActionBuilder(select(asterisk()).from(items)).as[Seq[Option[Any]]]
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

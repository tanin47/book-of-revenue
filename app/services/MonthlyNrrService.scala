package services

import database.models.JournalEntry
import database.models.JournalEntry.AccountCategory
import database.services.JournalEntryService
import database.services.JournalEntryService.{ColumnType, SortDirection, getMappedJournalEntries}
import framework.Helpers.{escapeCsv, formatCsvValue}
import framework.Jooq.*
import framework.{Instant, Jsonable, PeriodColumn}
import jooq.generated.stripe.Tables.STRIPE_CUSTOMER
import org.jooq.impl.DSL
import org.jooq.impl.DSL.*
import org.jooq.scalaextensions.Conversions.*
import org.jooq.{CommonTableExpression, OrderField, SortOrder}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import process.Helpers.generatePeriods
import slick.jdbc.{GetResult, JdbcProfile}

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.ZoneOffset
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

object MonthlyNrrService {
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
    maxCharacterLength: Int
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "id" -> (id match {
        case id: Column => id.name
        case period: PeriodColumn => period.name
      }),
      "type" -> tpe.toString,
      "maxCharacterLength" -> maxCharacterLength
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
class MonthlyNrrService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import MonthlyNrrService.*
  import framework.PostgresProfile.api.*

  def makeBaseSql(
    stripeAccountId: String,
    liveMode: Boolean,
    currency: String,
    periodStart: Instant,
    periodEnd: Instant
  ): CommonTableExpression[?] = {
    val revenueAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.Revenue).toList
    val contraRevenueAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.ContraRevenue).toList

    val mappedJournalEntries = getMappedJournalEntries()

    val rawEntries = name("raw_entries").as(
      `with`(mappedJournalEntries)
        .select(
          mappedJournalEntries.field("accounting_period"),
          mappedJournalEntries.field("customer_id"),
          sum(
            when(mappedJournalEntries.field("debit").in((revenueAccounts ++ contraRevenueAccounts).asJava), mappedJournalEntries.field("settlement_amount", classOf[java.lang.Long]).neg())
              .otherwise(0L)
              .add(when(mappedJournalEntries.field("credit").in((revenueAccounts ++ contraRevenueAccounts).asJava), mappedJournalEntries.field("settlement_amount", classOf[java.lang.Long])).otherwise(0L))
          ).as("net_revenue")
        )
        .from(mappedJournalEntries)
        .where(
          mappedJournalEntries.field("stripe_account_id", classOf[String]) === stripeAccountId,
          mappedJournalEntries.field("live_mode", classOf[Boolean]) === liveMode,
          mappedJournalEntries.field("accounting_period", classOf[Instant]) >= periodStart.atOffset(ZoneOffset.UTC).minusMonths(1).toInstant,
          mappedJournalEntries.field("accounting_period", classOf[Instant]) <= periodEnd,
          mappedJournalEntries.field("settlement_currency", classOf[String]) === currency
        )
        .groupBy(
          mappedJournalEntries.field("accounting_period"),
          mappedJournalEntries.field("customer_id")
        )
    )
    val e = rawEntries.as("e")
    val b = rawEntries.as("b")

    val eAccountingPeriod = e.field("accounting_period", classOf[Instant])
    val eCustomerId = e.field("customer_id", classOf[String])
    val eNetRevenue = e.field("net_revenue", classOf[java.lang.Long])

    val bAccountingPeriod = b.field("accounting_period", classOf[Instant])
    val bCustomerId = b.field("customer_id", classOf[String])
    val bNetRevenue = b.field("net_revenue", classOf[java.lang.Long])

    name("customer_entries").as(
      `with`(rawEntries)
        .select(
          coalesce(eAccountingPeriod, addMonthsUtc(bAccountingPeriod, 1)).as("accounting_period"),
          coalesce(eCustomerId, bCustomerId).as("customer_id"),
          (coalesce[java.lang.Long](eNetRevenue, 0L) * 100L / bNetRevenue).as("nrr")
        )
        .from(e)
        .rightJoin(b)
        .on(eAccountingPeriod.eq(addMonthsUtc(bAccountingPeriod, 1)))
        .and(eCustomerId.eq(bCustomerId))
        .where(bNetRevenue.gt(0L))
    )
  }

  def get(
    stripeAccountId: String,
    liveMode: Boolean,
    currency: String,
    periodStart: Instant,
    periodEnd: Instant
  ): Future[Seq[DataPoint]] = {
    db.run {
      val customerEntries = makeBaseSql(stripeAccountId, liveMode, currency, periodStart, periodEnd)

      toSqlActionBuilder(
        `with`(customerEntries)
          .select(
            field("accounting_period"),
            percentileCont(0.5).withinGroupOrderBy(field("nrr", classOf[java.lang.Long]).desc()).as("nrr")
          )
          .from(customerEntries)
          .groupBy(customerEntries.field("accounting_period"))
          .orderBy(customerEntries.field("accounting_period").asc())
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

  def makeBaseCustomerRevenueByMonthWithSql(
    stripeAccountId: String,
    liveMode: Boolean,
    params: CustomerRevenueByMonthParams
  ): CommonTableExpression[?] = {
    val periods = generatePeriods(params.periodStart, params.periodEnd.plusMillis(1))
    val sumPeriodColumns = periods.map { period =>
      sum(
        when(field("accounting_period", classOf[Instant]).eq(period.startedAt), field("nrr", classOf[java.lang.Long]))
          .otherwise(DSL.inline(0L))
      ).as(PeriodColumn(period.startedAt.toEpochMilli).name)
    }

    val keywordCond = if (params.keyword.isEmpty) {
      DSL.inline(true)
    } else {
      val modifiedKeyword = s"%${params.keyword}%"
      field("customer_id").likeIgnoreCase(modifiedKeyword)
        .or(field("c.name").likeIgnoreCase(modifiedKeyword))
        .or(field("c.email").likeIgnoreCase(modifiedKeyword))
    }

    val customerEntries = makeBaseSql(stripeAccountId, liveMode, params.currency, params.periodStart, params.periodEnd)
    val monthCustomerEntries = name("month_customer_entries").as(
      `with`(customerEntries)
        .select((Seq(field("customer_id")) ++ sumPeriodColumns).asJava)
        .from(customerEntries)
        .groupBy(field("customer_id"))
    )
    val e = monthCustomerEntries.as("e")
    val c = STRIPE_CUSTOMER.as("c")

    name("customer_entry_with_infos").as(
      `with`(monthCustomerEntries)
        .select(
          coalesce(e.field("customer_id"), c.ID).as("customer_id"),
          c.NAME.as("customer_name"),
          c.EMAIL.as("customer_email"),
          e.asterisk().except("customer_id")
        )
        .from(c)
        .leftJoin(e)
        .on(c.ID.eq(e.field("customer_id", classOf[String])))
        .where(
          c.STRIPE_ACCOUNT_ID.eq(stripeAccountId)
            .and(c.LIVE_MODE.eq(Boolean.box(liveMode)))
            .and(keywordCond)
        )
    )
  }

  def countCustomerRevenueByMonth(
    stripeAccountId: String,
    liveMode: Boolean,
    params: CustomerRevenueByMonthParams
  ): Future[Long] = {
    db
      .run {
        val customerEntryWithInfos = makeBaseCustomerRevenueByMonthWithSql(stripeAccountId, liveMode, params)

        toSqlActionBuilder(
          `with`(customerEntryWithInfos)
            .select(count(asterisk()))
            .from(customerEntryWithInfos)
        ).as[Long]
      }
      .map(_.headOption.getOrElse(0L))
  }

  private def getCustomerRevenueByMonthResultColumns(params: CustomerRevenueByMonthParams): Seq[CustomerRevenueByMonthResultColumn] = {
    val periods = generatePeriods(params.periodStart, params.periodEnd.plusMillis(1))
    Seq(
      CustomerRevenueByMonthResultColumn(id = Column.CustomerId, tpe = ColumnType.String, maxCharacterLength = 0),
      CustomerRevenueByMonthResultColumn(id = Column.CustomerName, tpe = ColumnType.String, maxCharacterLength = 0),
      CustomerRevenueByMonthResultColumn(id = Column.CustomerEmail, tpe = ColumnType.String, maxCharacterLength = 0),
    ) ++ periods.map { period =>
      CustomerRevenueByMonthResultColumn(id = PeriodColumn(period.startedAt.toEpochMilli), tpe = ColumnType.Percentage, maxCharacterLength = 0)
    }
  }

  private def makeCustomerOrderByClause(sorts: Seq[CustomerRevenueByMonthSort], periodEnd: Instant): Seq[OrderField[?]] = {
    if (sorts.isEmpty) {
      return Seq(
        field(quotedName(PeriodColumn(periodEnd.toEpochMilli).name)).desc().nullsLast(),
        field("customer_name").asc()
      )
    }

    sorts.map { sort =>
      sort.column match {
        case p: PeriodColumn =>
          field(quotedName(p.name)).sort(SortOrder.valueOf(sort.direction.toString.toUpperCase())).nullsLast()
        case c: Column =>
          val columnName = c match {
            case Column.CustomerId => "customer_id"
            case Column.CustomerName => "customer_name"
            case Column.CustomerEmail => "customer_email"
          }
          field(columnName).sort(SortOrder.valueOf(sort.direction.toString.toUpperCase())).nullsLast()
      }
    }
  }

  private def makeCustomerByMonthSql(stripeAccountId: String, liveMode: Boolean, params: CustomerRevenueByMonthParams): CommonTableExpression[?] = {
    val customerEntryWithInfos = makeBaseCustomerRevenueByMonthWithSql(stripeAccountId, liveMode, params)

    name("customer_by_month").as(
      `with`(customerEntryWithInfos)
        .select(asterisk())
        .from(customerEntryWithInfos)
        .orderBy(makeCustomerOrderByClause(params.sorts, params.periodEnd).asJava)
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
        val customerByMonth = makeCustomerByMonthSql(stripeAccountId, liveMode, params)

        toSqlActionBuilder(
          `with`(customerByMonth)
            .select(asterisk())
            .from(customerByMonth)
            .limit(limit)
            .offset(offset)
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
        val customerByMonth = makeCustomerByMonthSql(stripeAccountId, liveMode, params)

        toSqlActionBuilder(
          `with`(customerByMonth)
            .select(asterisk())
            .from(customerByMonth)
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

package services

import database.models.JournalEntry
import database.models.JournalEntry.AccountCategory
import database.services.JournalEntryService.{ColumnType, SortDirection, getMappedJournalEntries, getValue}
import database.services.MetronomeLineItemService.getMetronomeProductNames
import framework.Helpers.{escapeCsv, formatCsvValue}
import framework.Jooq.*
import framework.{Instant, Jsonable, PeriodColumn, PlayConfig}
import jooq.generated.metronome.Tables.METRONOME_LINE_ITEM
import jooq.generated.public.Tables.TRANSACTION
import jooq.generated.stripe.Tables.{STRIPE_CUSTOMER, STRIPE_INVOICE, STRIPE_INVOICE_LINE_ITEM, STRIPE_PRODUCT}
import org.jooq.impl.DSL
import org.jooq.impl.DSL.*
import org.jooq.scalaextensions.Conversions.*
import org.jooq.{CommonTableExpression, Field, SortField, SortOrder}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import process.Helpers.generatePeriods
import slick.jdbc.{GetResult, JdbcProfile}

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

object NetRevenueService {
  enum Column extends Enum[Column] {
    case
    AccountingPeriod,
    TransactionId,
    TransactionTitle,
    TransactionType,
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


  private def makeOrderByClause(sorts: Seq[Sort]): Seq[SortField[?]] = {
    if (sorts.isEmpty) {
      return Seq(
        field("accounting_period").asc(),
        field("net_revenue_net_income").desc().nullsLast(),
        field(quotedName("revenue_net_income")).desc().nullsLast(),
        field("settlement_currency").asc(),
      )
    }

    sorts.map { sort =>
      val name = sort.column match {
        case Column.AccountingPeriod => "accounting_period"
        case Column.TransactionId => "transaction_id"
        case Column.TransactionTitle => "transaction_title"
        case Column.TransactionType => "transaction_type"
        case Column.TransactionDate => "transaction_started_at"
        case Column.TransactionValue => "transaction_settlement_total_value"
        case Column.TransactionStatus => "transaction_status"
        case Column.CreditNotes => "creditnotes_net_income"
        case Column.Currency => "settlement_currency"
        case Column.CustomerEmail => "customer_email"
        case Column.CustomerId => "customer_id"
        case Column.CustomerName => "customer_name"
        case Column.Disputes => "disputes_net_income"
        case Column.GrossRevenue => "revenue_net_income"
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
        case Column.Refunds => "refunds_net_income"
        case Column.Voids => "voids_net_income"
      }

      field(quotedName(name)).sort(SortOrder.valueOf(sort.direction.toString.toUpperCase)).nullsLast()
    }
  }

  private def mapColumnToSqlColumn(col: Column): Field[?] = col match {
    case Column.AccountingPeriod => field("accounting_period")
    case Column.TransactionId => field("transaction_id")
    case Column.TransactionTitle => field("transaction_title")
    case Column.TransactionType => field("transaction_type")
    case Column.TransactionDate => field("transaction_started_at")
    case Column.TransactionValue => field("transaction_settlement_total_value")
    case Column.TransactionStatus => field("transaction_status")
    case Column.CreditNotes => field(quotedName("creditnotes_net_income"))
    case Column.Currency => field("settlement_currency")
    case Column.CustomerEmail => field("customer_email")
    case Column.CustomerId => field("customer_id")
    case Column.CustomerName => field("customer_name")
    case Column.Disputes => field(quotedName("disputes_net_income"))
    case Column.GrossRevenue => field(quotedName("revenue_net_income"))
    case Column.InvoiceId => field("invoice_id")
    case Column.InvoiceLineItemDescription => field("invoice_line_item_description")
    case Column.InvoiceLineItemEndedAt => field("invoice_line_item_ended_at")
    case Column.InvoiceLineItemId => field("invoice_line_item_id")
    case Column.InvoiceLineItemStartedAt => field("invoice_line_item_started_at")
    case Column.InvoiceNumber => field("invoice_number")
    case Column.NetRevenue => field("net_revenue_net_income")
    case Column.ProductId => field("product_id")
    case Column.ProductName => field("product_name")
    case Column.Refunds => field(quotedName("refunds_net_income"))
    case Column.Total => field("total")
    case Column.Voids => field(quotedName("voids_net_income"))
  }

  private def makeSelectedColumns(params: Params): Seq[Field[?]] = {
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

    if (params.groupBy.isEmpty) {
      computedColumns.map(mapColumnToSqlColumn)
    } else {
      computedColumns.map {
        case Column.AccountingPeriod => field("accounting_period")
        case Column.TransactionId => max(field("transaction_id")).as("transaction_id")
        case Column.TransactionTitle => max(field("transaction_title")).as("transaction_title")
        case Column.TransactionType => max(field("transaction_type")).as("transaction_type")
        case Column.TransactionDate => max(field("transaction_started_at")).as("transaction_started_at")
        case Column.TransactionValue => max(field("transaction_settlement_total_value")).as("transaction_settlement_total_value")
        case Column.TransactionStatus => max(field("transaction_status")).as("transaction_status")
        case Column.CreditNotes => sum(field(quotedName("creditnotes_net_income"), classOf[java.lang.Long])).as("creditnotes_net_income")
        case Column.Currency => field("settlement_currency")
        case Column.CustomerEmail => max(field("customer_email")).as("customer_email")
        case Column.CustomerId => max(field("customer_id")).as("customer_id")
        case Column.CustomerName => max(field("customer_name")).as("customer_name")
        case Column.Disputes => sum(field(quotedName("disputes_net_income"), classOf[java.lang.Long])).as("disputes_net_income")
        case Column.GrossRevenue => sum(field(quotedName("revenue_net_income"), classOf[java.lang.Long])).as("revenue_net_income")
        case Column.InvoiceId => max(field("invoice_id")).as("invoice_id")
        case Column.InvoiceLineItemDescription => max(field("invoice_line_item_description")).as("invoice_line_item_description")
        case Column.InvoiceLineItemEndedAt => max(field("invoice_line_item_ended_at")).as("invoice_line_item_ended_at")
        case Column.InvoiceLineItemId => max(field("invoice_line_item_id")).as("invoice_line_item_id")
        case Column.InvoiceLineItemStartedAt => min(field("invoice_line_item_started_at")).as("invoice_line_item_started_at")
        case Column.InvoiceNumber => max(field("invoice_number")).as("invoice_number")
        case Column.NetRevenue => sum(field("net_revenue_net_income", classOf[java.lang.Long])).as("net_revenue_net_income")
        case Column.ProductId => max(field("product_id")).as("product_id")
        case Column.ProductName => max(field("product_name")).as("product_name")
        case Column.Refunds => sum(field(quotedName("refunds_net_income"), classOf[java.lang.Long])).as("refunds_net_income")
        case Column.Total => sum(field("total", classOf[java.lang.Long])).as("total")
        case Column.Voids => sum(field(quotedName("voids_net_income"), classOf[java.lang.Long])).as("voids_net_income")
      }
    }
  }

  private def getResultColumns(params: Params): Seq[ResultColumn] = {
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
          case Column.TransactionId => ColumnType.String
          case Column.TransactionTitle => ColumnType.String
          case Column.TransactionType => ColumnType.String
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

  private def makeGroupByClause(params: Params): Seq[Field[?]] = {
    params.groupBy
      .map {
        case GroupBy.Product => Seq(field("product_id"))
        case GroupBy.Customer => Seq(field("customer_id"))
        case GroupBy.Transaction => Seq(field("transaction_id"))
        case GroupBy.LineItem => Seq(field("transaction_id"), field("invoice_line_item_id"))
        case GroupBy.Summary => Seq.empty
      }
      .map { extraGroupKey =>
        Seq(field("settlement_currency"), field("accounting_period")) ++ extraGroupKey
      }
      .getOrElse(Seq.empty)
  }

  def makeBaseWithSql(stripeAccountId: String, liveMode: Boolean, params: Params): CommonTableExpression[?] = {
    val whereClause = Seq(
      Some(field("stripe_account_id") === stripeAccountId),
      Some(field("live_mode") === liveMode),
      Some(field("settlement_currency") === params.currency),
      Some(field("accounting_period") >= params.periodStart),
      Some(field("accounting_period") <= params.periodEnd),
      params.productId.map { productId => field("product_id") === productId },
      params.customerId.map { customerId => field("customer_id") === customerId },
      params.transactionId.map { transactionId => field("transaction_id") === transactionId },
    ).flatten

    val revenueAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.Revenue).toList
    val contraRevenueAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.ContraRevenue).toList

    val groupWhereClause = params.showOnly
      .map {
        case ShowOnly.GrossRevenue => field(quotedName("revenue_net_income")) !== 0
        case ShowOnly.NetRevenue => field("net_revenue_net_income") !== 0
        case ShowOnly.Disputes => field(quotedName("disputes_net_income")) !== 0
        case ShowOnly.Refunds => field(quotedName("refunds_net_income")) !== 0
        case ShowOnly.Voids => field(quotedName("voids_net_income")) !== 0
        case ShowOnly.CreditNotes => field(quotedName("creditnotes_net_income")) !== 0
      }
      .getOrElse(
        or((
          Seq(
            field(quotedName("revenue_net_income")) !== 0,
            field("net_revenue_net_income") !== 0
          ) ++
              contraRevenueAccounts.map { contraAccount => field(quotedName(s"${contraAccount.name.toLowerCase()}_net_income")) !== 0 }
        ).asJava)
      )

    val contraAccountColumns = contraRevenueAccounts.map { contraAccount =>
      when(field("credit") === contraAccount.name, field("settlement_amount")).otherwise(0L)
        .add(when(field("debit") === contraAccount.name, field("settlement_amount").neg()).otherwise(0L))
        .as(s"${contraAccount.name.toLowerCase}_net_income")
    }

    val mappedJournalEntries = getMappedJournalEntries()
    val rawEntries = name("raw_entries").as(
      `with`(mappedJournalEntries)
        .select((
          Seq(
            asterisk(),
            when(field("credit").in(revenueAccounts.asJava), field("settlement_amount")).otherwise(0L)
              .add(when(field("debit").in(revenueAccounts.asJava), field("settlement_amount").neg()).otherwise(0L))
              .as("revenue_net_income"),
            when(field("credit").in((revenueAccounts ++ contraRevenueAccounts).asJava), field("settlement_amount")).otherwise(0L)
              .add(when(field("debit").in((revenueAccounts ++ contraRevenueAccounts).asJava), field("settlement_amount").neg()).otherwise(0L))
              .as("net_revenue_net_income"),
          ) ++ contraAccountColumns
        ).asJava)
        .from(mappedJournalEntries)
        .where(whereClause.asJava)
    )
    val metronomeProductNames = getMetronomeProductNames()
    val entries = name("entries").as(
      `with`(rawEntries, metronomeProductNames)
      .select(
        rawEntries.asterisk(),
        TRANSACTION.TITLE.as("transaction_title"),
        STRIPE_CUSTOMER.NAME.as("customer_name"),
        STRIPE_CUSTOMER.EMAIL.as("customer_email"),
        STRIPE_INVOICE.NUMBER.as("invoice_number"),
        STRIPE_INVOICE_LINE_ITEM.DESCRIPTION.as("invoice_line_item_description"),
        STRIPE_INVOICE_LINE_ITEM.STARTED_AT.as("invoice_line_item_started_at"),
        STRIPE_INVOICE_LINE_ITEM.ENDED_AT.as("invoice_line_item_ended_at"),
        coalesce(metronomeProductNames.field("name"), STRIPE_PRODUCT.NAME).as("product_name")
      )
        .from(rawEntries)
        .leftJoin(TRANSACTION).on(TRANSACTION.ID === rawEntries.field("transaction_id", classOf[String]))
        .leftJoin(STRIPE_CUSTOMER).on(STRIPE_CUSTOMER.ID === rawEntries.field("customer_id", classOf[String]))
        .leftJoin(STRIPE_INVOICE).on(STRIPE_INVOICE.ID === rawEntries.field("invoice_id", classOf[String]))
        .leftJoin(STRIPE_INVOICE_LINE_ITEM).on(STRIPE_INVOICE_LINE_ITEM.ID === rawEntries.field("invoice_line_item_id", classOf[String]))
        .leftJoin(STRIPE_PRODUCT).on(STRIPE_PRODUCT.ID === rawEntries.field("product_id", classOf[String]))
        .leftJoin(metronomeProductNames).on(metronomeProductNames.field("id", classOf[String]) === rawEntries.field("product_id", classOf[String]))
    )
    val rawGroups = name("raw_groups").as(
      `with`(entries)
        .select(makeSelectedColumns(params).asJava)
        .from(entries)
        .groupBy(makeGroupByClause(params).asJava)
    )

    name("groups").as(
      `with`(rawGroups)
        .select(rawGroups.asterisk())
        .from(rawGroups)
        .where(groupWhereClause)
    )
  }

  def count(stripeAccountId: String, liveMode: Boolean, params: Params): Future[Long] = {
    db
      .run {
        val groups = makeBaseWithSql(stripeAccountId, liveMode, params)
        toSqlActionBuilder(
          `with`(groups)
            .select(DSL.count(asterisk()))
            .from(groups)
        ).as[Long]
      }
      .map(_.headOption.getOrElse(0L))
  }


  def get(stripeAccountId: String, liveMode: Boolean, params: Params, offset: Int, limit: Int): Future[Result] = {
    val resultColumns = getResultColumns(params)
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns)
    db
      .run {
        val groups = makeBaseWithSql(stripeAccountId, liveMode, params)
        toSqlActionBuilder(
          `with`(groups)
            .select(asterisk())
            .from(groups)
            .orderBy(makeOrderByClause(params.sorts).asJava)
            .limit(limit)
            .offset(offset)
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
        val groups = makeBaseWithSql(stripeAccountId, liveMode, params)
        toSqlActionBuilder(
          `with`(groups)
            .select(asterisk())
            .from(groups)
            .orderBy(makeOrderByClause(params.sorts).asJava)
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

  private def makeRevenueByMonthOrderByClause(sorts: Seq[RevenueByMonthSort], groupBy: GroupBy): Seq[SortField[?]] = {
    if (sorts.isEmpty) {
      groupBy match {
        case GroupBy.Product => return Seq(field("total").desc().nullsLast(), field("product_name").asc())
        case GroupBy.Customer => return Seq(field("total").desc().nullsLast(), field("customer_name").asc())
        case GroupBy.Transaction => return Seq(
          field("transaction_started_at").desc().nullsLast(),
          field("transaction_settlement_total_value").desc()
        )
        case _ => throw new IllegalArgumentException(s"Invalid groupBy: ${groupBy}")
      }
    }

    sorts.flatMap { sort =>
      sort.column match {
        case p: PeriodColumn =>
          Seq(field(p.name).sort(SortOrder.valueOf(sort.direction.toString.toUpperCase)).nullsLast())
        case c: Column =>
          makeOrderByClause(Seq(Sort(c, sort.direction)))
      }
    }
  }

  private def makeBaseRevenueByMonthWithSql(stripeAccountId: String, liveMode: Boolean, params: RevenueByMonthParams): CommonTableExpression[?] = {
    val periods = generatePeriods(params.periodStart, params.periodEnd.plusMillis(1))
    val sumPeriodColumns = periods.map { period =>
      sum(when(field("accounting_period") === period.startedAt, field("net_revenue_net_income", classOf[java.lang.Long])).otherwise(0L)).as(PeriodColumn(period.startedAt.toEpochMilli).name)
    }

    val keywordCond = if (params.keyword.isEmpty) {
      DSL.trueCondition()
    } else {
      val modifiedKeyword = s"%${params.keyword}%"
      or(field("customer_id").likeIgnoreCase(modifiedKeyword), STRIPE_CUSTOMER.NAME.likeIgnoreCase(modifiedKeyword), STRIPE_CUSTOMER.EMAIL.likeIgnoreCase(modifiedKeyword))
    }

    val groups = makeBaseWithSql(
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
          Column.TransactionId,
          Column.ProductId,
          Column.NetRevenue,
        ),
        sorts = Seq.empty
      )
    )

    params.groupBy match {
      case GroupBy.Product =>
        val rawRevenueByMonthGroups = name("raw_revenue_by_month_groups").as(
          `with`(groups)
            .select((
              Seq(
                field("product_id"),
                sum(field("net_revenue_net_income", classOf[java.lang.Long])).as("total")
              ) ++ sumPeriodColumns
            ).asJava)
            .from(groups)
            .groupBy(field("product_id"))
        )

        name("revenue_by_month_groups").as(
          `with`(rawRevenueByMonthGroups)
            .select(
              rawRevenueByMonthGroups.asterisk().except("product_id"),
              coalesce(rawRevenueByMonthGroups.field("product_id"), STRIPE_PRODUCT.ID).as("product_id"),
              STRIPE_PRODUCT.NAME.as("product_name"),
            )
            .from(rawRevenueByMonthGroups)
            .leftJoin(STRIPE_PRODUCT).on(rawRevenueByMonthGroups.field("product_id", classOf[String]) === STRIPE_PRODUCT.ID)
            .where(keywordCond)
        )

      case GroupBy.Customer =>
        val rawRevenueByMonthGroups = name("raw_revenue_by_month_groups").as(
          `with`(groups)
            .select((
              Seq(
                field("customer_id"),
                sum(field("net_revenue_net_income", classOf[java.lang.Long])).as("total")
              ) ++ sumPeriodColumns
            ).asJava)
            .from(groups)
            .groupBy(field("customer_id"))
        )

        name("revenue_by_month_groups").as(
          `with`(rawRevenueByMonthGroups)
            .select((
              Seq(
                rawRevenueByMonthGroups.asterisk(),
                STRIPE_CUSTOMER.NAME.as("customer_name"),
                STRIPE_CUSTOMER.EMAIL.as("customer_email"),
              )
            ).asJava)
            .from(rawRevenueByMonthGroups)
            .fullOuterJoin(STRIPE_CUSTOMER).on(rawRevenueByMonthGroups.field("customer_id", classOf[String]) === STRIPE_CUSTOMER.ID)
            .where(STRIPE_CUSTOMER.STRIPE_ACCOUNT_ID === stripeAccountId, STRIPE_CUSTOMER.LIVE_MODE === liveMode, keywordCond)
        )
      case GroupBy.Transaction =>
        val rawRevenueByMonthGroups = name("raw_revenue_by_month_groups").as(
          `with`(groups)
            .select((
              Seq(
                field("transaction_id"),
                sum(field("net_revenue_net_income", classOf[java.lang.Long])).as("total")
              ) ++ sumPeriodColumns
            ).asJava)
            .from(groups)
            .groupBy(field("transaction_id"))
        )

        name("revenue_by_month_groups").as(
          `with`(rawRevenueByMonthGroups)
            .select(
              rawRevenueByMonthGroups.asterisk().except("transaction_id"),
              coalesce(rawRevenueByMonthGroups.field("transaction_id"), TRANSACTION.ID).as("transaction_id"),
              TRANSACTION.TITLE.as("transaction_title"),
              TRANSACTION.SETTLEMENT_TOTAL_VALUE.as("transaction_settlement_total_value"),
              TRANSACTION.TYPE.as("transaction_type"),
              TRANSACTION.STATUS.as("transaction_status"),
              TRANSACTION.STARTED_AT.as("transaction_started_at"),
            )
            .from(TRANSACTION)
            .fullOuterJoin(rawRevenueByMonthGroups).on(TRANSACTION.ID === rawRevenueByMonthGroups.field("transaction_id", classOf[String]))
            .where(
              TRANSACTION.STRIPE_ACCOUNT_ID === stripeAccountId,
              TRANSACTION.LIVE_MODE === liveMode,
              params.customerId match {
                case None => TRANSACTION.CUSTOMER_ID.isNull()
                case Some(customerId) => TRANSACTION.CUSTOMER_ID === customerId
              }
            )
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
        val revenueByMonthGroups = makeBaseRevenueByMonthWithSql(stripeAccountId, liveMode, params)


        toSqlActionBuilder(
          `with`(revenueByMonthGroups)
            .select(DSL.count(asterisk()))
            .from(revenueByMonthGroups)
        ).as[Long]
      }
      .map(_.headOption.getOrElse(0L))
  }

  private def getRevenueByMonthResultColumns(params: RevenueByMonthParams): Seq[RevenueByMonthResultColumn] = {
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
          RevenueByMonthResultColumn(id = Column.TransactionId, tpe = ColumnType.String),
          RevenueByMonthResultColumn(id = Column.TransactionTitle, tpe = ColumnType.String),
          RevenueByMonthResultColumn(id = Column.TransactionValue, tpe = ColumnType.Amount),
          RevenueByMonthResultColumn(id = Column.TransactionType, tpe = ColumnType.String),
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

  def makeRevenueByMonthSelectedColumns(columns: Seq[RevenueByMonthResultColumn]): Seq[Field[?]] = {
    columns.map { column =>
      column.id match {
        case c: Column => mapColumnToSqlColumn(c)
        case p: PeriodColumn => field(quotedName(p.name))
      }
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
        val revenueByMonthGroups = makeBaseRevenueByMonthWithSql(stripeAccountId, liveMode, params)

        toSqlActionBuilder(
          `with`(revenueByMonthGroups)
            .select(makeRevenueByMonthSelectedColumns(resultColumns).asJava)
            .from(revenueByMonthGroups)
            .orderBy(makeRevenueByMonthOrderByClause(params.sorts, params.groupBy).asJava)
            .limit(limit)
            .offset(offset)
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
        val revenueByMonthGroups = makeBaseRevenueByMonthWithSql(stripeAccountId, liveMode, params)
        toSqlActionBuilder(
          `with`(revenueByMonthGroups)
            .select(asterisk())
            .from(revenueByMonthGroups)
            .orderBy(makeRevenueByMonthOrderByClause(params.sorts, params.groupBy).asJava)
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

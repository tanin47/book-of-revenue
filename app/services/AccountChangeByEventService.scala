package services

import database.models.JournalEntry
import database.services.JournalEntryService
import database.services.JournalEntryService.{ColumnType, SortDirection}
import database.services.MetronomeLineItemService.getMetronomeProductNames
import framework.Helpers.{escapeCsv, formatCsvValue}
import framework.{EventColumn, Instant, Jsonable}
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
import org.jooq.{CommonTableExpression, Condition, Field, SelectSeekStepN, SortField, SortOrder}

import scala.jdk.CollectionConverters.SeqHasAsJava

object AccountChangeByEventService {
  case class DataPointAmount(
    event: String,
    value: Long
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "id" -> event,
      "value" -> value
    )
  }

  case class DataPoint(
    period: Instant,
    amounts: Seq[DataPointAmount]
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "period" -> period.toEpochMilli,
      "values" -> amounts.map(_.toJson())
    )
  }

  enum Column extends Enum[Column] {
    case
    AccountingPeriod,
    TransactionId,
    TransactionTitle,
    CustomerEmail,
    CustomerId,
    CustomerName,
    InvoiceId,
    InvoiceLineItemDescription,
    InvoiceLineItemEndedAt,
    InvoiceLineItemId,
    InvoiceLineItemStartedAt,
    InvoiceNumber,
    NetChange,
    ProductId,
    ProductName
  }


  case class Sort(column: Column | EventColumn, direction: SortDirection)

  enum GroupBy extends Enum[GroupBy] {
    case Summary, Product, Customer, Transaction, LineItem
  }

  case class Params(
    periodStart: Instant,
    periodEnd: Instant,
    currency: String,
    groupBy: GroupBy,
    showOnly: Option[Column | EventColumn],
    productId: Option[String],
    customerId: Option[String],
    transactionId: Option[String],
    account: JournalEntry.Account,
    columns: Seq[Column],
    sorts: Seq[Sort]
  )

  case class ResultColumn(
    id: Column | EventColumn,
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
class AccountChangeByEventService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  balanceSheetService: BalanceSheetService
)(implicit ec: ExecutionContext)  extends HasDatabaseConfigProvider[JdbcProfile] {
  import AccountChangeByEventService.*
  import framework.PostgresProfile.api.*

  def getChangesByMonth(
    stripeAccountId: String,
    liveMode: Boolean,
    currency: String,
    periodStart: Instant,
    periodEnd: Instant,
    account: JournalEntry.Account
  ): Future[Seq[DataPoint]] = {
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
        accounts = Seq(account.name),
        columns = Seq(
          BalanceSheetService.Column.AccountingPeriod,
          BalanceSheetService.Column.Category,
          BalanceSheetService.Column.Account,
          BalanceSheetService.Column.Event,
          BalanceSheetService.Column.NetChange,
        ),
        sorts = Seq(BalanceSheetService.Sort(BalanceSheetService.Column.AccountingPeriod, SortDirection.Asc))
      ),
      0,
      100000
    )
      .map { result =>
        val entriesByPeriod = result.rows.groupBy(_.head.asInstanceOf[Option[Long]].get)

        generatePeriods(periodStart, periodEnd.plusMillis(1)).map { period =>
          DataPoint(
            period = period.startedAt,
            amounts = entriesByPeriod.getOrElse(period.startedAt.toEpochMilli, Seq.empty).map { entry =>
              DataPointAmount(
                event = entry.apply(3).asInstanceOf[Option[String]].get,
                value = entry.apply(4).asInstanceOf[Option[Long]].get
              )
            }
          )
        }
      }
  }

  private def getRelevantEvents(accounts: Seq[JournalEntry.Account]): Future[Seq[JournalEntry.Event]] = {
    db
      .run {
        sql"""
          SELECT DISTINCT COALESCE(reversed_event, event) FROM journal_entry WHERE debit = ANY(${accounts.map(_.name)}) OR credit = ANY(${accounts.map(_.name)})
        """.as[String]
      }
      .map { events => events.map(JournalEntry.Event.valueOf) }
  }

  private def makeGroupKeys(params: Params): Seq[String] = {
    val base = Seq("accounting_period")
    val extraGroupKeys = params.groupBy match {
      case GroupBy.Summary => Seq.empty
      case GroupBy.Product => Seq("product_id")
      case GroupBy.Customer => Seq("customer_id")
      case GroupBy.Transaction => Seq("transaction_id")
      case GroupBy.LineItem => Seq("transaction_id, invoice_line_item_id")
    }

    base ++ extraGroupKeys
  }

  def makeBaseWithSql(
    stripeAccountId: String,
    liveMode: Boolean,
    params: Params,
    events: Seq[JournalEntry.Event],
  ): CommonTableExpression[?] = {
    val eventColumnsSql = events.map { event =>
      sum(when(field("computed_event", classOf[Any]) === event.name, field("net_settlement_change", classOf[java.lang.Long])).otherwise(0L)).as(EventColumn(event).name)
    }

    val keys = makeGroupKeys(params)

    val netChanges = balanceSheetService.makeNetChangeSql(
      stripeAccountId = stripeAccountId,
      liveMode = liveMode,
      params = BalanceSheetService.Params(
        periodStart = Some(params.periodStart),
        periodEnd = Some(params.periodEnd),
        groupBy = params.groupBy match {
          case GroupBy.Summary => Some(BalanceSheetService.GroupBy.Summary)
          case GroupBy.Product => Some(BalanceSheetService.GroupBy.Product)
          case GroupBy.Customer => Some(BalanceSheetService.GroupBy.Customer)
          case GroupBy.Transaction => Some(BalanceSheetService.GroupBy.Transaction)
          case GroupBy.LineItem => Some(BalanceSheetService.GroupBy.LineItem)
        },
        groupBy2 = Some(BalanceSheetService.GroupBy2.Event),
        currency = params.currency,
        showOnly = None,
        productId = params.productId,
        customerId = params.customerId,
        transactionId = params.transactionId,
        accounts = Seq(params.account.name),
        columns = Seq(
          BalanceSheetService.Column.AccountingPeriod,
          BalanceSheetService.Column.Event,
          BalanceSheetService.Column.Account,
          BalanceSheetService.Column.NetChange,
        ),
        sorts = Seq.empty
      ),
      forCumulative = false
    )

    val groupBys = keys.map { k => field(k) }
    val rawGroups = name("raw_groups").as(
      `with`(netChanges)
        .select((
          Seq(field("accounting_period")) ++
            eventColumnsSql ++
            Seq(
              max(field("net_settlement_change", classOf[Any])).as("net_settlement_change"),
              max(field("customer_id", classOf[Any])).as("customer_id"),
              max(field("transaction_id", classOf[Any])).as("transaction_id"),
              max(field("invoice_id", classOf[Any])).as("invoice_id"),
              max(field("invoice_line_item_id", classOf[Any])).as("invoice_line_item_id")
            )
        ).asJava)
        .from(netChanges)
        .groupBy(groupBys.asJava)
    )

    val whereCond = params.showOnly match {
      case Some(Column.NetChange) => field("net_settlement_change") !== 0
      case Some(col: EventColumn) => field(quotedName(col.name)) !== 0
      case Some(_) => throw new IllegalArgumentException(s"Invalid showOnly: ${params.showOnly}")
      case None =>
        or((
          Seq(field("net_settlement_change") !== 0) ++
            events.map { event =>
              field(quotedName(EventColumn(event).name)) !== 0
            },
        ).asJava)
    }

    name("groups").as(
      `with`(rawGroups)
        .select(asterisk())
        .from(rawGroups)
        .where(whereCond)
    )
  }

  def count(
    stripeAccountId: String,
    liveMode: Boolean,
    params: Params,
  ): Future[Long] = {
    for {
      events <- getRelevantEvents(Seq(params.account))
      result <- db.run {
        val groups = makeBaseWithSql(stripeAccountId, liveMode, params, events)
        toSqlActionBuilder(`with`(groups).select(DSL.count(asterisk())).from(groups)).as[Long]
      }
    } yield {
      result.headOption.getOrElse(0L)
    }
  }

  def makeListSql(
    stripeAccountId: String,
    liveMode: Boolean,
    params: Params,
    events: Seq[JournalEntry.Event],
  ): SelectSeekStepN[?] = {
    val groups = makeBaseWithSql(stripeAccountId, liveMode, params, events)
    val metronomeProductNames = getMetronomeProductNames()
    val groupWithLookupColumns = name("group_with_lookup_columns").as(
      `with`(groups, metronomeProductNames)
        .select(
          groups.asterisk(),
          TRANSACTION.TITLE.as("transaction_title"),
          STRIPE_CUSTOMER.NAME.as("customer_name"),
          STRIPE_CUSTOMER.EMAIL.as("customer_email"),
          STRIPE_INVOICE.NUMBER.as("invoice_number"),
          STRIPE_INVOICE_LINE_ITEM.DESCRIPTION.as("invoice_line_item_description"),
          STRIPE_INVOICE_LINE_ITEM.STARTED_AT.as("invoice_line_item_started_at"),
          STRIPE_INVOICE_LINE_ITEM.ENDED_AT.as("invoice_line_item_ended_at"),
          coalesce(metronomeProductNames.field("name", classOf[String]), STRIPE_PRODUCT.NAME).as("product_name")
        )
        .from(groups)
        .leftJoin(TRANSACTION).on(TRANSACTION.ID === groups.field("transaction_id", classOf[String]))
        .leftJoin(STRIPE_CUSTOMER).on(STRIPE_CUSTOMER.ID === groups.field("customer_id", classOf[String]))
        .leftJoin(STRIPE_INVOICE).on(STRIPE_INVOICE.ID === groups.field("invoice_id", classOf[String]))
        .leftJoin(STRIPE_INVOICE_LINE_ITEM).on(STRIPE_INVOICE_LINE_ITEM.ID === groups.field("invoice_line_item_id", classOf[String]))
        .leftJoin(STRIPE_PRODUCT).on(STRIPE_PRODUCT.ID === groups.field("product_id", classOf[String]))
        .leftJoin(metronomeProductNames).on(metronomeProductNames.field("id", classOf[String]) === groups.field("product_id", classOf[String]))
    )

    `with`(groupWithLookupColumns)
      .select(makeSelectedColumns(params, events).asJava)
      .from(groupWithLookupColumns)
      .orderBy(makeOrderByClause(params.sorts).asJava)
  }

  private def computeColumns(params: Params, events: Seq[JournalEntry.Event]): Seq[Column | EventColumn] = {
    (params.columns ++ events.map(EventColumn.apply)).asInstanceOf[Seq[Column | EventColumn]]
      .sortBy {
        case e: EventColumn =>
          e.event match {
            case JournalEntry.Event.CreateCharge => 2
            case JournalEntry.Event.PayFee => 3
            case JournalEntry.Event.RefundCharge => 4
            case JournalEntry.Event.FailRefund => 5
            case JournalEntry.Event.DisputeCharge => 6
            case JournalEntry.Event.WinDispute => 7
            case _ => 8
          }
        case Column.AccountingPeriod => 0
        case _ => 10000
      }
  }

  private def makeSelectedColumns(params: Params, events: Seq[JournalEntry.Event]): Seq[Field[?]] = {
      computeColumns(params, events)
        .map {
          case e: EventColumn => e.name
          case Column.AccountingPeriod => "accounting_period"
          case Column.NetChange => "net_settlement_change"
          case Column.ProductId => "product_id"
          case Column.ProductName => "product_name"
          case Column.CustomerId => "customer_id"
          case Column.CustomerName => "customer_name"
          case Column.CustomerEmail => "customer_email"
          case Column.TransactionId => "transaction_id"
          case Column.TransactionTitle => "transaction_title"
          case Column.InvoiceId => "invoice_id"
          case Column.InvoiceNumber => "invoice_number"
          case Column.InvoiceLineItemDescription => "invoice_line_item_description"
          case Column.InvoiceLineItemId => "invoice_line_item_id"
          case Column.InvoiceLineItemStartedAt => "invoice_line_item_started_at"
          case Column.InvoiceLineItemEndedAt => "invoice_line_item_ended_at"
        }
        .map { s => field(quotedName(s)) }
  }

  private def makeOrderByClause(sorts: Seq[Sort]): Seq[SortField[?]] = {
    if (sorts.isEmpty) {
      return Seq(field("accounting_period").asc(), field("net_settlement_change").desc())
    }

    sorts.map { sort =>
      val name = sort.column match {
        case e: EventColumn => e.name
        case Column.AccountingPeriod => "accounting_period"
        case Column.NetChange => "net_settlement_change"
        case Column.ProductId => "product_id"
        case Column.ProductName => "product_name"
        case Column.CustomerId => "customer_id"
        case Column.CustomerName => "customer_name"
        case Column.CustomerEmail => "customer_email"
        case Column.TransactionId => "transaction_id"
        case Column.TransactionTitle => "transaction_title"
        case Column.InvoiceId => "invoice_id"
        case Column.InvoiceNumber => "invoice_number"
        case Column.InvoiceLineItemId => "invoice_line_item_id"
        case Column.InvoiceLineItemDescription => "invoice_line_item_description"
        case Column.InvoiceLineItemStartedAt => "invoice_line_item_started_at"
        case Column.InvoiceLineItemEndedAt => "invoice_line_item_ended_at"
      }

      field(quotedName(name)).sort(SortOrder.valueOf(sort.direction.toString.toUpperCase)).nullsLast()
    }
  }

  private def getResultColumns(params: Params, events: Seq[JournalEntry.Event]): Seq[ResultColumn] = {
    computeColumns(params, events).map { column =>
      ResultColumn(
        id = column,
        tpe = column match {
          case _: EventColumn => ColumnType.Amount
          case Column.AccountingPeriod => ColumnType.Period
          case Column.NetChange => ColumnType.Amount
          case Column.ProductId => ColumnType.String
          case Column.ProductName => ColumnType.String
          case Column.CustomerId => ColumnType.String
          case Column.CustomerName => ColumnType.String
          case Column.CustomerEmail => ColumnType.String
          case Column.TransactionId => ColumnType.String
          case Column.TransactionTitle => ColumnType.String
          case Column.InvoiceId => ColumnType.String
          case Column.InvoiceNumber => ColumnType.String
          case Column.InvoiceLineItemDescription => ColumnType.String
          case Column.InvoiceLineItemId => ColumnType.String
          case Column.InvoiceLineItemStartedAt => ColumnType.Timestamp
          case Column.InvoiceLineItemEndedAt => ColumnType.Timestamp
        },
      )
    }
  }

  def get(
    stripeAccountId: String,
    liveMode: Boolean,
    params: Params,
    offset: Int,
    limit: Int
  ): Future[Result] = {
    for {
      events <- getRelevantEvents(Seq(params.account))
      result <- {
        val resultColumns = getResultColumns(params, events)
        implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns)

        db.run {
          val list = makeListSql(stripeAccountId, liveMode, params, events)
          toSqlActionBuilder(
            select(asterisk())
              .from(list)
              .limit(limit)
              .offset(offset)
          ).as[Seq[Option[Any]]]
        }.map { rows =>
          Result(resultColumns, rows.toList)
        }
      }
    } yield {
      result
    }
  }

  def exportToCsv(stripeAccountId: String, liveMode: Boolean, params: Params): Future[File] = {
    val destinationFile = Files.createTempFile("direct-cash-flow", ".csv").toFile
    for {
      events <- getRelevantEvents(Seq(params.account))
      _ <- {
        val resultColumns = getResultColumns(params, events).toArray
        implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns.toList)

        val writer = new BufferedWriter(new FileWriter(destinationFile, StandardCharsets.UTF_8))
        writer.write(resultColumns.map { column => escapeCsv(column.id.toString) }.mkString(","))
        writer.newLine()

        db
          .stream {
            val list = makeListSql(stripeAccountId, liveMode, params, events)
            toSqlActionBuilder(select(asterisk()).from(list)).as[Seq[Option[Any]]]
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
      destinationFile
    }
  }
}

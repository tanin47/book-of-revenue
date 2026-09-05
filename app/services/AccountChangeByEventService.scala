package services

import database.models.JournalEntry
import database.services.JournalEntryService
import database.services.JournalEntryService.{ColumnType, SortDirection}
import framework.Helpers.{escapeCsv, formatCsvValue}
import framework.{EventColumn, Instant, Jsonable}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import process.Helpers.generatePeriods
import services.AccountChangeByEventService.GroupBy
import slick.jdbc.{GetResult, JdbcProfile, SQLActionBuilder}
import slick.sql.SqlAction

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

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
    RevRecTransactionId,
    RevRecTransactionTitle,
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

  private[this] def getRelevantEvents(accounts: Seq[JournalEntry.Account]): Future[Seq[JournalEntry.Event]] = {
    db
      .run {
        sql"""
          SELECT DISTINCT COALESCE(reversed_event, event) FROM journal_entry WHERE debit = ANY(${accounts.map(_.name)}) OR credit = ANY(${accounts.map(_.name)})
        """.as[String]
      }
      .map { events => events.map(JournalEntry.Event.valueOf) }
  }

  private[this] def makeGroupKeys(params: Params): Seq[String] = {
    val base = Seq("accounting_period")
    val extraGroupKeys = params.groupBy match {
      case GroupBy.Summary => Seq.empty
      case GroupBy.Product => Seq("product_id")
      case GroupBy.Customer => Seq("customer_id")
      case GroupBy.Transaction => Seq("rev_rec_transaction_id")
      case GroupBy.LineItem => Seq("rev_rec_transaction_id, invoice_line_item_id")
    }

    base ++ extraGroupKeys
  }

  def makeBaseWithSql(
    stripeAccountId: String,
    liveMode: Boolean,
    params: Params,
    events: Seq[JournalEntry.Event],
  ): SQLActionBuilder = {
    val eventColumnsSql = joinSqls(
      events.map { event =>
        sql"""
          SUM(CASE WHEN computed_event = ${event.name} THEN net_settlement_change ELSE 0 END) AS "#${EventColumn(event).name}"
        """
      },
      sql", "
    )
    val whereSql = params.showOnly match {
      case Some(Column.NetChange) => sql"net_settlement_change != 0"
      case Some(col: EventColumn) => sql""""#${col.name}" != 0"""
      case Some(_) => throw new IllegalArgumentException(s"Invalid showOnly: ${params.showOnly}")
      case None =>
        makeSql(
          sql"net_settlement_change != 0 OR ",
          joinSqls(
            events.map { event =>
              sql""""#${EventColumn(event).name}" != 0"""
            },
            sql" OR "
          )
        )
    }

    val keys = makeGroupKeys(params)
    val groupByClause = joinSqls(keys.map { k => sql"#$k" }, sql", ")

    makeSql(
      balanceSheetService.makeNetChangeSql(
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
      ),
      sql"""
        ,

        raw_groups AS (
          SELECT
            accounting_period,
      """,
      eventColumnsSql,
      sql"""
            ,
            SUM(net_settlement_change) AS net_settlement_change,
            MAX(customer_id) AS customer_id,
            MAX(rev_rec_transaction_id) AS rev_rec_transaction_id,
            MAX(invoice_id) AS invoice_id,
            MAX(invoice_line_item_id) AS invoice_line_item_id
          FROM net_changes
          GROUP BY
      """,
      groupByClause,
      sql"""
        ),
        groups AS (
          SELECT * FROM raw_groups
          WHERE
      """,
      whereSql,
      sql"""
        )
      """
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
        makeSql(
          makeBaseWithSql(stripeAccountId, liveMode, params, events),
          sql"""
            SELECT COUNT(*) FROM groups
          """
        ).as[Long]
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
  ): SQLActionBuilder = {
    makeSql(
      makeBaseWithSql(stripeAccountId, liveMode, params, events),
      sql"""
        ,

        group_with_lookup_columns AS(
          SELECT
            main.*,
            con.title AS rev_rec_transaction_title,
            cus.name AS customer_name,
            cus.email AS customer_email,
            inv.number AS invoice_number,
            il.description AS invoice_line_item_description,
            il.started_at AS invoice_line_item_started_at,
            il.ended_at AS invoice_line_item_ended_at,
            price.product_id AS product_id,
            product.name AS product_name
          FROM groups main
          LEFT JOIN rev_rec_transaction con ON con.id = main.rev_rec_transaction_id
          LEFT JOIN customer cus ON cus.id = main.customer_id
          LEFT JOIN invoice inv ON inv.id = main.invoice_id
          LEFT JOIN invoice_line_item il ON il.id = main.invoice_line_item_id
          LEFT JOIN price ON price.id = il.price_id
          LEFT JOIN product ON product.id = price.product_id
        )

        SELECT
      """,
      makeSelectedColumns(params, events),
      sql"""
         FROM group_with_lookup_columns
         ORDER BY
      """,
      makeOrderByClause(params.sorts),
    )
  }

  private[this] def computeColumns(params: Params, events: Seq[JournalEntry.Event]): Seq[Column | EventColumn] = {
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

  private[this] def makeSelectedColumns(params: Params, events: Seq[JournalEntry.Event]): SQLActionBuilder = {
    joinSqls(
      computeColumns(params, events).map {
        case e: EventColumn => sql""""#${e.name}""""
        case Column.AccountingPeriod => sql"accounting_period"
        case Column.NetChange => sql"net_settlement_change"
        case Column.ProductId => sql"product_id"
        case Column.ProductName => sql"product_name"
        case Column.CustomerId => sql"customer_id"
        case Column.CustomerName => sql"customer_name"
        case Column.CustomerEmail => sql"customer_email"
        case Column.RevRecTransactionId => sql"rev_rec_transaction_id"
        case Column.RevRecTransactionTitle => sql"rev_rec_transaction_title"
        case Column.InvoiceId => sql"invoice_id"
        case Column.InvoiceNumber => sql"invoice_number"
        case Column.InvoiceLineItemDescription => sql"invoice_line_item_description"
        case Column.InvoiceLineItemId => sql"invoice_line_item_id"
        case Column.InvoiceLineItemStartedAt => sql"invoice_line_item_started_at"
        case Column.InvoiceLineItemEndedAt => sql"invoice_line_item_ended_at"
      },
      sql", "
    )
  }

  private[this] def makeOrderByClause(sorts: Seq[Sort]): SQLActionBuilder = {
    if (sorts.isEmpty) {
      return sql"accounting_period ASC, net_settlement_change DESC"
    }

    val sortClauses = sorts.map { sort =>
      val name = sort.column match {
        case e: EventColumn => e.name
        case Column.AccountingPeriod => "accounting_period"
        case Column.NetChange => "net_settlement_change"
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
      }

      sql""""#${name}" #${sort.direction.toString.toUpperCase} NULLS LAST"""
    }

    joinSqls(sortClauses, sql", ")
  }

  private[this] def getResultColumns(params: Params, events: Seq[JournalEntry.Event]): Seq[ResultColumn] = {
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
          case Column.RevRecTransactionId => ColumnType.String
          case Column.RevRecTransactionTitle => ColumnType.String
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
          makeSql(
            makeListSql(stripeAccountId, liveMode, params, events),
            sql" LIMIT $limit OFFSET $offset"
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
            makeListSql(stripeAccountId, liveMode, params, events).as[Seq[Option[Any]]]
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

package services

import database.models.JournalEntry
import database.services.JournalEntryService
import database.services.JournalEntryService.{ColumnType, SortDirection, getMappedJournalEntries, getValue}
import database.services.MetronomeLineItemService.getMetronomeProductNames
import framework.Helpers.{escapeCsv, formatCsvValue}
import framework.Jooq.*
import framework.{Instant, Jsonable, PlayConfig}
import jooq.generated.public.Tables.TRANSACTION
import jooq.generated.stripe.Tables.{STRIPE_CUSTOMER, STRIPE_INVOICE, STRIPE_INVOICE_LINE_ITEM, STRIPE_PRODUCT}
import org.jooq.impl.DSL
import org.jooq.impl.DSL.*
import org.jooq.scalaextensions.Conversions.*
import org.jooq.{CommonTableExpression, Condition, Field, SelectSeekStepN, SortField, SortOrder}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import slick.jdbc.{GetResult, JdbcProfile}

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.charset.StandardCharsets
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.SeqHasAsJava
import scala.language.implicitConversions

object DebitsAndCreditsService {
  enum Column extends Enum[Column] {
    case
    AccountingPeriod,
    AttributionPeriod,
    Debit,
    Credit,
    Amount,
    Event,
    ProductId,
    ProductName,
    CustomerId,
    CustomerName,
    CustomerEmail,
    TransactionId,
    TransactionTitle,
    InvoiceId,
    InvoiceNumber,
    InvoiceLineItemDescription,
    InvoiceLineItemId,
    InvoiceLineItemStartedAt,
    InvoiceLineItemEndedAt,
    ReversedEvent,
    OccurredAt
  }
  case class Sort(column: Column, direction: SortDirection)
  enum GroupBy extends Enum[GroupBy] {
    case Summary, Product, Customer, Transaction, LineItem
  }
  case class Params(
    periodStart: Option[Instant],
    periodEnd: Option[Instant],
    transactionId: Option[String],
    groupBy: Option[GroupBy],
    currency: String,
    lineItemId: Option[String],
    accounts: Seq[String],
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

  case class AccountSummaryEntry(
    account: JournalEntry.Account,
    accountingPeriod: Instant,
    settlementAmount: Long,
    settlementCurrency: String,
    presentmentAmount: Long,
    presentmentCurrency: String,
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "account" -> account.toString,
      "accountingPeriod" -> accountingPeriod.toEpochMilli,
      "settlementAmount" -> settlementAmount,
      "settlementCurrency" -> settlementCurrency,
      "presentmentAmount" -> presentmentAmount,
      "presentmentCurrency" -> presentmentCurrency,
    )
  }
}

@Singleton
class DebitsAndCreditsService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  journalEntryService: JournalEntryService,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import DebitsAndCreditsService.*
  import framework.PostgresProfile.api.*


  private def makeOrderByClause(sorts: Seq[Sort]): Seq[SortField[?]] = {
    if (sorts.isEmpty) {
      return Seq(
        field("accounting_period").asc,
        field("debit").asc,
        field("credit").asc
      )
    }

    sorts.map { sort =>
      val name = sort.column match {
        case Column.AccountingPeriod => "accounting_period"
        case Column.AttributionPeriod => "attribution_period"
        case Column.Debit => "debit"
        case Column.Credit => "credit"
        case Column.Amount => "amount"
        case Column.Event => "event"
        case Column.ReversedEvent => "reversed_event"
        case Column.OccurredAt => "occurred_at"
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

      field(name).sort(SortOrder.valueOf(sort.direction.toString.toUpperCase))
    }
  }

  private def makeSelectedColumns(params: Params): Seq[Field[?]] = {
    if (params.groupBy.isEmpty) {
      params.columns.map {
        case Column.AccountingPeriod => field("accounting_period")
        case Column.AttributionPeriod => field("attribution_period")
        case Column.Debit => field("debit")
        case Column.Credit => field("credit")
        case Column.Amount => field("settlement_amount").as("amount")
        case Column.Event => field("event")
        case Column.ReversedEvent => field("reversed_event")
        case Column.OccurredAt => field("occurred_at")
        case Column.ProductId => field("product_id")
        case Column.ProductName => field("product_name")
        case Column.CustomerId => field("customer_id")
        case Column.CustomerName => field("customer_name")
        case Column.CustomerEmail => field("customer_email")
        case Column.TransactionId => field("transaction_id")
        case Column.TransactionTitle => field("transaction_title")
        case Column.InvoiceId => field("invoice_id")
        case Column.InvoiceNumber => field("invoice_number")
        case Column.InvoiceLineItemDescription => field("invoice_line_item_description")
        case Column.InvoiceLineItemId => field("invoice_line_item_id")
        case Column.InvoiceLineItemStartedAt => field("invoice_line_item_started_at")
        case Column.InvoiceLineItemEndedAt => field("invoice_line_item_ended_at")
      }
    } else {
      params.columns.map {
        case Column.AccountingPeriod => field("accounting_period")
        case Column.Debit => field("debit")
        case Column.Credit => field("credit")
        case Column.Amount => sum(field("settlement_amount", classOf[java.lang.Long])).as("amount")
        case Column.ProductId => max(field("product_id")).as("product_id")
        case Column.ProductName => max(field("product_name")).as("product_name")
        case Column.CustomerId => max(field("customer_id")).as("customer_id")
        case Column.CustomerName => max(field("customer_name")).as("customer_name")
        case Column.CustomerEmail => max(field("customer_email")).as("customer_email")
        case Column.TransactionId => max(field("transaction_id")).as("transaction_id")
        case Column.TransactionTitle => max(field("transaction_title")).as("transaction_title")
        case Column.InvoiceId => max(field("invoice_id")).as("invoice_id")
        case Column.InvoiceNumber => max(field("invoice_number")).as("invoice_number")
        case Column.InvoiceLineItemDescription => max(field("invoice_line_item_description")).as("invoice_line_item_description")
        case Column.InvoiceLineItemId => max(field("invoice_line_item_id")).as("invoice_line_item_id")
        case Column.InvoiceLineItemStartedAt => min(field("invoice_line_item_started_at")).as("invoice_line_item_started_at")
        case Column.InvoiceLineItemEndedAt => max(field("invoice_line_item_ended_at")).as("invoice_line_item_ended_at")
        case other => throw new Exception(s"Invalid column in the grouping mode: $other")
      }
    }
  }

  private def getResultColumns(params: Params): Seq[ResultColumn] = {
    params.columns.map { column =>
      ResultColumn(
        id = column,
        tpe = column match {
          case Column.AccountingPeriod => ColumnType.Period
          case Column.AttributionPeriod => ColumnType.Period
          case Column.Debit => ColumnType.String
          case Column.Credit => ColumnType.String
          case Column.Amount => ColumnType.Amount
          case Column.Event => ColumnType.String
          case Column.ReversedEvent => ColumnType.String
          case Column.OccurredAt => ColumnType.Timestamp
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

  private def makeGroupByClause(params: Params): Seq[Field[?]] = {
    params.groupBy
      .map {
        case GroupBy.Product => Seq(field("product_id"))
        case GroupBy.Customer => Seq(field("customer_id"))
        case GroupBy.Transaction => Seq(field("transaction_id"))
        case GroupBy.LineItem => Seq(field("transaction_id"), field("invoice_line_item_id"))
        case GroupBy.Summary => Seq.empty
      }
      .map { extraGroupKeys =>
        Seq(field("accounting_period"), field("debit"), field("credit")) ++ extraGroupKeys
      }
      .getOrElse(Seq.empty)
  }

  private def makeBaseWithSql(stripeAccountId: String, liveMode: Boolean, params: Params): CommonTableExpression[?] = {
    val mappedJournalEntries = getMappedJournalEntries()
    val j = mappedJournalEntries.as("j")
    def jField(columnName: String): Field[Any] = field(name("j", columnName), classOf[Any])

    val whereClause: Seq[Condition] = Seq(
      Some(jField("stripe_account_id") === stripeAccountId),
      Some(jField("live_mode") === liveMode),
      Some(jField("settlement_currency") === params.currency),
      params.periodStart.map { p => field("accounting_period") >= p },
      params.periodEnd.map { p => field("accounting_period") <= p },
      params.transactionId.map { c => jField("transaction_id") === c },
      params.lineItemId.map { c => jField("invoice_line_item_id") === c },
      if (params.accounts.nonEmpty) {
        Some(field("debit").in(params.accounts.asJava).or(field("credit").in(params.accounts.asJava)))
      } else {
        None
      }
    ).flatten

    val metronomeProductNames = getMetronomeProductNames()

    val entries = name("entries").as(
      `with`(mappedJournalEntries, metronomeProductNames)
        .select(
          j.asterisk(),
          STRIPE_CUSTOMER.NAME.as("customer_name"),
          STRIPE_CUSTOMER.EMAIL.as("customer_email"),
          STRIPE_INVOICE.NUMBER.as("invoice_number"),
          STRIPE_INVOICE_LINE_ITEM.DESCRIPTION.as("invoice_line_item_description"),
          STRIPE_INVOICE_LINE_ITEM.STARTED_AT.as("invoice_line_item_started_at"),
          STRIPE_INVOICE_LINE_ITEM.ENDED_AT.as("invoice_line_item_ended_at"),
          coalesce(metronomeProductNames.field("name"), STRIPE_PRODUCT.NAME).as("product_name"),
          TRANSACTION.TITLE.as("transaction_title")
        )
        .from(j)
        .leftJoin(STRIPE_CUSTOMER).on(jField("customer_id") === STRIPE_CUSTOMER.ID)
        .leftJoin(STRIPE_INVOICE).on(jField("invoice_id") === STRIPE_INVOICE.ID)
        .leftJoin(STRIPE_INVOICE_LINE_ITEM).on(jField("invoice_line_item_id") === STRIPE_INVOICE_LINE_ITEM.ID)
        .leftJoin(STRIPE_PRODUCT).on(jField("product_id") === STRIPE_PRODUCT.ID)
        .leftJoin(metronomeProductNames).on(jField("product_id") === metronomeProductNames.field("id"))
        .leftJoin(TRANSACTION).on(jField("transaction_id") === TRANSACTION.ID)
        .where(whereClause.asJava)
    )

    val groupBys = makeGroupByClause(params)
    val groupSelect = `with`(entries)
      .select(makeSelectedColumns(params).asJava)
      .from(entries)

    name("groups").as(
      if (groupBys.isEmpty) { groupSelect } else { groupSelect.groupBy(groupBys.asJava) }
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


  private def makeListSql(stripeAccountId: String, liveMode: Boolean, params: Params): SelectSeekStepN[?] = {
    val groups = makeBaseWithSql(stripeAccountId, liveMode, params)

    `with`(groups)
      .select(asterisk())
      .from(groups)
      .orderBy(makeOrderByClause(params.sorts).asJava)
  }

  def get(stripeAccountId: String, liveMode: Boolean, params: Params, offset: Int, limit: Int): Future[Result] = {
    val resultColumns = getResultColumns(params)
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns)
    db
      .run {
        val list = makeListSql(stripeAccountId, liveMode, params)
        toSqlActionBuilder(
          select(asterisk())
            .from(list)
            .limit(limit)
            .offset(offset)
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
        val list = makeListSql(stripeAccountId, liveMode, params)
        toSqlActionBuilder(
          select(asterisk())
            .from(list)
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

  def getAllAccounts(stripeAccountId: String, liveMode: Boolean): Future[Seq[String]] = {
    val whereClause: Seq[Condition] = Seq(
      field("stripe_account_id") === stripeAccountId,
      field("live_mode") === liveMode
    )

    db.run {
      val mappedJournalEntries = getMappedJournalEntries()

      val debits = name("debits").as(
        `with`(mappedJournalEntries)
          .selectDistinct(field("debit").as("account"))
          .from(mappedJournalEntries)
          .where(whereClause.asJava)
      )
      val credits = name("credits").as(
        `with`(mappedJournalEntries)
          .selectDistinct(field("credit").as("account"))
          .from(mappedJournalEntries)
          .where(whereClause.asJava)
      )
      val combined = name("combined").as(
        `with`(debits, credits)
          .select(asterisk()).from(debits)
          .unionAll(select(asterisk()).from(credits))
      )

      toSqlActionBuilder(
        `with`(combined)
          .selectDistinct(field("account"))
          .from(combined)
          .orderBy(field("account").asc)
      ).as[String]
    }
  }

  def getAccountSummary(
    stripeAccountId: String,
    liveMode: Boolean,
    transactionId: String,
    lineItemId: Option[String]
  ): Future[Seq[AccountSummaryEntry]] = {
    journalEntryService.getByTransactionId(stripeAccountId, liveMode, transactionId, lineItemId).map { entries =>
      entries
        .flatMap { entry =>
          Seq(
            AccountSummaryEntry(
              account = entry.debit,
              accountingPeriod = entry.accountingPeriod,
              settlementAmount = if (entry.debit.isCredit()) { -entry.settlementAmount } else { entry.settlementAmount },
              settlementCurrency = entry.settlementCurrency,
              presentmentAmount = if (entry.debit.isCredit()) { -entry.presentmentAmount } else { entry.presentmentAmount },
              presentmentCurrency = entry.presentmentCurrency,
            ),
            AccountSummaryEntry(
              account = entry.credit,
              accountingPeriod = entry.accountingPeriod,
              settlementAmount = if (entry.credit.isCredit()) { entry.settlementAmount } else { -entry.settlementAmount },
              settlementCurrency = entry.settlementCurrency,
              presentmentAmount = if (entry.credit.isCredit()) { entry.presentmentAmount } else { -entry.presentmentAmount },
              presentmentCurrency = entry.presentmentCurrency,
            ),
          )
        }
        .groupBy { entry => (entry.account, entry.accountingPeriod) }
        .values
        .map { entries =>
          entries.head.copy(
            settlementAmount = entries.map(_.settlementAmount).sum,
            presentmentAmount = entries.map(_.presentmentAmount).sum,
          )
        }
        .toList
    }
  }
}

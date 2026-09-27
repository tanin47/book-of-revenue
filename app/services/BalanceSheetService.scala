package services

import database.models.JournalEntry
import database.models.JournalEntry.{AccountCategory, getAccountCategoryField, getCategorySqlCond}
import database.services.JournalEntryService.{ColumnType, SortDirection, getMappedJournalEntries, getValue}
import database.services.MetronomeLineItemService.getMetronomeProductNames
import framework.Helpers.{escapeCsv, formatCsvValue}
import framework.{Instant, Jsonable, PlayConfig}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import process.Helpers.generatePeriods
import slick.jdbc.{GetResult, JdbcProfile, SQLActionBuilder}

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.charset.StandardCharsets
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

object BalanceSheetService {
  enum Column extends Enum[Column] {
    case
    Account,
    AccountingPeriod,
    Category,
    TransactionId,
    TransactionTitle,
    CustomerEmail,
    CustomerId,
    CustomerName,
    EndingBalance,
    Event,
    InvoiceId,
    InvoiceLineItemDescription,
    InvoiceLineItemEndedAt,
    InvoiceLineItemId,
    InvoiceLineItemStartedAt,
    InvoiceNumber,
    NetChange,
    ProductId,
    ProductName,
    StartingBalance
  }
  case class Sort(column: Column, direction: SortDirection)
  enum GroupBy extends Enum[GroupBy] {
    case Summary, Event, Product, Customer, Transaction, LineItem
  }
  enum GroupBy2 extends Enum[GroupBy2] {
    case Event
  }
  case class Params(
    periodStart: Option[Instant],
    periodEnd: Option[Instant],
    groupBy: Option[GroupBy],
    groupBy2: Option[GroupBy2],
    currency: String,
    showOnly: Option[Column],
    productId: Option[String],
    customerId: Option[String],
    transactionId: Option[String],
    accounts: Seq[String],
    columns: Seq[Column],
    sorts: Seq[Sort]
  )

  case class ResultColumn(
    id: Column,
    tpe: ColumnType,
    maxCharacterLength: Int
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "id" -> id.toString,
      "type" -> tpe.toString,
      "maxCharacterLength" -> maxCharacterLength
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

@Singleton
class BalanceSheetService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import BalanceSheetService.*
  import framework.PostgresProfile.api.*


  private def makeOrderByClause(sorts: Seq[Sort]): Seq[SortField[?]] = {
    if (sorts.isEmpty) {
      return Seq(
        field("accounting_period").asc,
        field("category").asc,
        field("net_settlement_change").desc,
        field("account").asc
      )
    }

    sorts.map { sort =>
      val name = sort.column match {
        case Column.AccountingPeriod => "accounting_period"
        case Column.Account => "account"
        case Column.StartingBalance => "settlement_starting_balance"
        case Column.EndingBalance => "settlement_ending_balance"
        case Column.Category => "category"
        case Column.NetChange => "net_settlement_change"
        case Column.Event => "computed_event"
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
      params.columns
        .map {
          case Column.AccountingPeriod => "accounting_period"
          case Column.Account => "account"
          case Column.Category => "category"
          case Column.NetChange => "net_settlement_change"
          case Column.Event => "computed_event"
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
          case Column.StartingBalance => "settlement_starting_balance"
          case Column.EndingBalance => "settlement_ending_balance"
        }
        .map { s => field(s) }
  }

  private def getResultColumns(params: Params): Seq[ResultColumn] = {
    params.columns.map { column =>
      ResultColumn(
        id = column,
        tpe = column match {
          case Column.AccountingPeriod => ColumnType.Period
          case Column.Account => ColumnType.String
          case Column.StartingBalance => ColumnType.Amount
          case Column.EndingBalance => ColumnType.Amount
          case Column.Category => ColumnType.String
          case Column.NetChange => ColumnType.DeltaAmount
          case Column.Event => ColumnType.String
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
        maxCharacterLength = 0
      )
    }
  }

  private def makeGroupKeys(params: Params): Seq[String] = {
    val base = Seq("accounting_period", "account")
    val extraGroupKeys = params.groupBy
      .map {
        case GroupBy.Product => Seq("product_id")
        case GroupBy.Event => Seq("computed_event")
        case GroupBy.Customer => Seq("customer_id")
        case GroupBy.Transaction => Seq("transaction_id")
        case GroupBy.LineItem => Seq("transaction_id", "invoice_line_item_id")
        case GroupBy.Summary => Seq.empty
      }
      .getOrElse(Seq.empty)
    val extraGroupKeys2 = params.groupBy2
      .map {
        case GroupBy2.Event => Seq("computed_event")
      }
      .getOrElse(Seq.empty)

    base ++ extraGroupKeys ++ extraGroupKeys2
  }

  def makeNetChangeSql(stripeAccountId: String, liveMode: Boolean, params: Params, forCumulative: Boolean): CommonTableExpression[?] = {
    def makeWhereClause(isDebit: Boolean): Seq[Condition] = Seq(
      Some(field("stripe_account_id") === stripeAccountId),
      Some(field("live_mode") === liveMode),
      if (!forCumulative) {
        params.periodStart.map { p => field("accounting_period") >= p }
      } else {
        None
      },
      params.periodEnd.map { p => field("accounting_period") <= p },
      params.productId.map { c => field("product_id") === c },
      params.customerId.map { c => field("customer_id") === c },
      params.transactionId.map { c => field("transaction_id") === c },
      if (params.accounts.nonEmpty) {
        val account = if (isDebit) { "debit" } else { "credit" }
        Some(field(account).in(params.accounts.asJava))
      } else {
        None
      }
    ).flatten

    val assetAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.Asset).toList
    val contraAssetAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.ContraAsset).toList
    val contractLiabilityAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.ContractLiability).toList
    val statutoryLiabilityAccounts = JournalEntry.Account.values.filter(_.getAccountCategory() == AccountCategory.StatutoryLiability).toList

    val keys = makeGroupKeys(params)
    val groupBys = keys.map { k => field(k) }

    val mappedJournalEntries = getMappedJournalEntries()

    val creditEntries = name("credit_entries").as(
      `with`(mappedJournalEntries)
        .select(
          asterisk(),
          coalesce(field("reversed_event", classOf[String]), field("event")).as("computed_event"),
          field("credit").as("account"),
          when(field("credit").in((assetAccounts ++ contraAssetAccounts).asJava), field("settlement_amount").neg())
            .when(field("credit").in((contractLiabilityAccounts ++ statutoryLiabilityAccounts).asJava), field("settlement_amount"))
            .otherwise(0L)
            .as("net_settlement_change")
        )
        .from(mappedJournalEntries)
        .where(makeWhereClause(isDebit = false).asJava)
    )

    val debitEntries = name("debit_entries").as(
      `with`(mappedJournalEntries)
        .select(
          asterisk(),
          coalesce(field("reversed_event", classOf[String]), field("event")).as("computed_event"),
          field("debit").as("account"),
          when(field("debit").in((assetAccounts ++ contraAssetAccounts).asJava), field("settlement_amount"))
            .when(field("debit").in((contractLiabilityAccounts ++ statutoryLiabilityAccounts).asJava), field("settlement_amount").neg())
            .otherwise(0L)
            .as("net_settlement_change")
        )
        .from(mappedJournalEntries)
        .where(makeWhereClause(isDebit = true).asJava)
    )

    val rawEntries = name("raw_entries").as(
      `with`(creditEntries, debitEntries)
        .select(asterisk()).from(creditEntries)
        .unionAll(select(asterisk()).from(debitEntries))
    )

    val entries = name("entries").as(
      `with`(rawEntries)
        .select(asterisk())
        .from(rawEntries)
        .where(
          field("net_settlement_change") !== 0,
          field("settlement_currency") === params.currency
        )
    )

    name("net_changes").as(
      `with`(entries)
        .select(
          field("accounting_period"),
          field("account"),
          max(field("computed_event")).as("computed_event"),
          sum(field("net_settlement_change", classOf[java.lang.Long])).as("net_settlement_change"),
          max(field("customer_id")).as("customer_id"),
          max(field("transaction_id")).as("transaction_id"),
          max(field("invoice_id")).as("invoice_id"),
          max(field("invoice_line_item_id")).as("invoice_line_item_id"),
          max(field("product_id")).as("product_id")
        )
        .from(entries)
        .groupBy(groupBys.asJava)
    )
  }

  def makeBaseWithSql(stripeAccountId: String, liveMode: Boolean, params: Params): CommonTableExpression[?] = {
    val keys = makeGroupKeys(params)

    val netChanges = makeNetChangeSql(stripeAccountId, liveMode, params, forCumulative = true)

    val main = netChanges.as("main")
    val sub = netChanges.as("sub")

    val cumulativeJoinConditions = keys.filter(_ != "accounting_period").map { k => main.field(k, classOf[Any]).isNotDistinctFrom(sub.field(k)) }
    val cumulativeGroupBys = keys.map { k => main.field(k) }

    val sumNetSettlementChangeField = sum(when(main.field("accounting_period", classOf[Any]) === sub.field("accounting_period"), sub.field("net_settlement_change", classOf[java.lang.Long])).otherwise(0))

    val rawGroups = name("raw_groups").as(
      `with`(netChanges)
        .select(
          main.field("accounting_period"),
          main.field("account"),
          getAccountCategoryField(main.field("account", classOf[String])).as("category"),
          sumNetSettlementChangeField.as("net_settlement_change"),
          (sum(sub.field("net_settlement_change", classOf[java.lang.Long])) - sumNetSettlementChangeField).as("settlement_starting_balance"),
          sum(sub.field("net_settlement_change", classOf[java.lang.Long])).as("settlement_ending_balance"),
          max(main.field("computed_event", classOf[Any])).as("computed_event"),
          max(main.field("customer_id", classOf[Any])).as("customer_id"),
          max(main.field("transaction_id", classOf[Any])).as("transaction_id"),
          max(main.field("invoice_id", classOf[Any])).as("invoice_id"),
          max(main.field("invoice_line_item_id", classOf[Any])).as("invoice_line_item_id"),
          max(main.field("product_id", classOf[Any])).as("product_id")
        )
        .from(main)
        .leftJoin(sub)
        .on(and((
          Seq(sub.field("accounting_period", classOf[Any]) <= main.field("accounting_period")) ++ cumulativeJoinConditions
        ).asJava))
        .groupBy(cumulativeGroupBys.asJava)
    )

    val periods = name("periods").as(
      select(
        field("to_timestamp(period)").as("period")
      )
        .from(unnest(generatePeriods(params.periodStart.get, params.periodEnd.get.plusMillis(1)).map(_.startedAt.getEpochSecond).asJava).as("period"))
    )

    val distinctOnFields = keys.filter(_ != "accounting_period").map { k => rawGroups.field(k) }
    val unfilteredGroups = name("unfiltered_groups").as(
      `with`(rawGroups, periods)
        .select(
          rawGroups.field("account"),
          rawGroups.field("category"),
          periods.field("period").as("accounting_period"),
          when(periods.field("period", classOf[Any]) === rawGroups.field("accounting_period"), rawGroups.field("net_settlement_change", classOf[java.lang.Long]))
            .otherwise(0L)
            .as("net_settlement_change"),
          when(periods.field("period", classOf[Any]) === rawGroups.field("accounting_period"), rawGroups.field("settlement_starting_balance", classOf[java.lang.Long]))
            .otherwise(rawGroups.field("settlement_ending_balance", classOf[java.lang.Long]))
            .as("settlement_starting_balance"),
          rawGroups.field("settlement_ending_balance"),
          rawGroups.field("computed_event"),
          rawGroups.field("customer_id"),
          rawGroups.field("transaction_id"),
          rawGroups.field("invoice_id"),
          rawGroups.field("invoice_line_item_id"),
          rawGroups.field("product_id")
        )
        .distinctOn((
          Seq(periods.field("period")) ++ distinctOnFields
        ).asJava)
        .from(rawGroups)
        .join(periods)
        .on(rawGroups.field("accounting_period", classOf[Any]) <= periods.field("period"))
        .orderBy((
          Seq(periods.field("period")) ++ distinctOnFields ++ Seq(rawGroups.field("accounting_period").desc)
        ).asJava)
    )

    val groupWheres = params.showOnly match {
      case Some(Column.StartingBalance) => Seq(field("settlement_starting_balance") !== 0)
      case Some(Column.EndingBalance) => Seq(field("settlement_ending_balance") !== 0)
      case Some(Column.NetChange) => Seq(field("net_settlement_change") !== 0)
      case Some(_) => throw new Exception(s"showOnly is not supported: ${params.showOnly}")
      case None => Seq.empty
    }

    name("groups").as(
      `with`(unfilteredGroups)
        .select(asterisk())
        .from(unfilteredGroups)
        .where(groupWheres.asJava)
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

  def makeBaseWithSqlWithLookupColumns(stripeAccountId: String, liveMode: Boolean, params: Params): CommonTableExpression[?] = {
    val groups = makeBaseWithSql(stripeAccountId, liveMode, params)
    val metronomeProductNames = getMetronomeProductNames()

    name("group_with_lookup_columns").as(
      `with`(groups, metronomeProductNames)
        .select(
          groups.asterisk(),
          STRIPE_CUSTOMER.NAME.as("customer_name"),
          STRIPE_CUSTOMER.EMAIL.as("customer_email"),
          STRIPE_INVOICE.NUMBER.as("invoice_number"),
          STRIPE_INVOICE_LINE_ITEM.DESCRIPTION.as("invoice_line_item_description"),
          STRIPE_INVOICE_LINE_ITEM.STARTED_AT.as("invoice_line_item_started_at"),
          STRIPE_INVOICE_LINE_ITEM.ENDED_AT.as("invoice_line_item_ended_at"),
          coalesce(metronomeProductNames.field("name"), STRIPE_PRODUCT.NAME).as("product_name"),
          TRANSACTION.TITLE.as("transaction_title")
        )
        .from(groups)
        .leftJoin(STRIPE_CUSTOMER).on(groups.field("customer_id", classOf[Any]) === STRIPE_CUSTOMER.ID)
        .leftJoin(STRIPE_INVOICE).on(groups.field("invoice_id", classOf[Any]) === STRIPE_INVOICE.ID)
        .leftJoin(STRIPE_INVOICE_LINE_ITEM).on(groups.field("invoice_line_item_id", classOf[Any]) === STRIPE_INVOICE_LINE_ITEM.ID)
        .leftJoin(STRIPE_PRODUCT).on(groups.field("product_id", classOf[Any]) === STRIPE_PRODUCT.ID)
        .leftJoin(metronomeProductNames).on(groups.field("product_id", classOf[Any]) === metronomeProductNames.field("id"))
        .leftJoin(TRANSACTION).on(groups.field("transaction_id", classOf[Any]) === TRANSACTION.ID)
    )
  }

  private def makeListSql(stripeAccountId: String, liveMode: Boolean, params: Params): SelectSeekStepN[?] = {
    val groups = makeBaseWithSqlWithLookupColumns(stripeAccountId, liveMode, params)

    `with`(groups)
      .select(makeSelectedColumns(params).asJava)
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
}

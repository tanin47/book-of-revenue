package services

import database.models.JournalEntry
import database.services.JournalEntryService.{ColumnType, SortDirection, getMappedJournalEntries, getValue}
import framework.Helpers.{escapeCsv, formatCsvValue}
import framework.Jooq.*
import framework.{Instant, Jsonable, PlayConfig}
import jooq.generated.public.Tables.TRANSACTION
import jooq.generated.stripe.Tables.{STRIPE_CUSTOMER, STRIPE_INVOICE}
import org.jooq.impl.DSL
import org.jooq.impl.DSL.*
import org.jooq.scalaextensions.Conversions.*
import org.jooq.{CommonTableExpression, Condition, Field, SelectSeekStepN, SortField, SortOrder}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import slick.jdbc.{GetResult, JdbcProfile}

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.SeqHasAsJava
import scala.language.implicitConversions

object ArAgingService {
  enum Column extends Enum[Column] {
    case
      Date,
      NotDue,
      Days30,
      Days60,
      Days90,
      Days120,
      Days120Plus,
      Total,
      OccurredAt,
      CustomerId,
      CustomerName,
      CustomerEmail,
      TransactionId,
      TransactionTitle,
      InvoiceId,
      InvoiceNumber
  }

  case class Sort(column: Column, direction: SortDirection)
  enum GroupBy extends Enum[GroupBy] {
    case Summary, Customer, Transaction
  }
  case class Params(
    exclusiveUpUntil: Instant,
    groupBy: GroupBy,
    currency: String,
    customerId: Option[String],
    columns: Seq[Column],
    sorts: Seq[ArAgingService.Sort],
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
}

class ArAgingService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import ArAgingService.*
  import framework.PostgresProfile.api.*

  private def makeOrderByClause(sorts: Seq[Sort]): Seq[SortField[?]] = {
    if (sorts.isEmpty) {
      return Seq(
        field("total").desc,
        field("days_120_plus").desc,
        field("days_120").desc,
        field("days_90").desc,
        field("days_60").desc,
        field("days_30").desc
      )
    }

    sorts.map { sort =>
      val name = sort.column match {
        case Column.Date => "date"
        case Column.NotDue => "not_due"
        case Column.Days30 => "days_30"
        case Column.Days60 => "days_60"
        case Column.Days90 => "days_90"
        case Column.Days120 => "days_120"
        case Column.Days120Plus => "days_120_plus"
        case Column.Total => "total"
        case Column.OccurredAt => "occurred_at"
        case Column.CustomerId => "customer_id"
        case Column.CustomerName => "customer_name"
        case Column.CustomerEmail => "customer_email"
        case Column.TransactionId => "transaction_id"
        case Column.TransactionTitle => "transaction_title"
        case Column.InvoiceId => "invoice_id"
        case Column.InvoiceNumber => "invoice_number"
      }

      field(name).sort(SortOrder.valueOf(sort.direction.toString.toUpperCase))
    }
  }

  private def makeGroupByClause(params: Params): Seq[Field[?]] = {
    params.groupBy match {
      case GroupBy.Customer => Seq(field("customer_id"))
      case GroupBy.Transaction => Seq(field("transaction_id"))
      case GroupBy.Summary => Seq.empty
    }
  }

  private def makeSelectedColumns(params: Params): Seq[Field[?]] = {
    params.columns.map {
      case Column.Date => DSL.`val`(params.exclusiveUpUntil.minusMillis(1)).as("date")
      case Column.NotDue => sum(field("not_due", classOf[java.lang.Long])).as("not_due")
      case Column.Days30 => sum(field("days_30", classOf[java.lang.Long])).as("days_30")
      case Column.Days60 => sum(field("days_60", classOf[java.lang.Long])).as("days_60")
      case Column.Days90 => sum(field("days_90", classOf[java.lang.Long])).as("days_90")
      case Column.Days120 => sum(field("days_120", classOf[java.lang.Long])).as("days_120")
      case Column.Days120Plus => sum(field("days_120_plus", classOf[java.lang.Long])).as("days_120_plus")
      case Column.Total => sum(field("total", classOf[java.lang.Long])).as("total")
      case Column.OccurredAt => min(field("occurred_at")).as("occurred_at")
      case Column.CustomerId => min(field("customer_id")).as("customer_id")
      case Column.CustomerName => min(field("customer_name")).as("customer_name")
      case Column.CustomerEmail => min(field("customer_email")).as("customer_email")
      case Column.TransactionId => min(field("transaction_id")).as("transaction_id")
      case Column.TransactionTitle => min(field("transaction_title")).as("transaction_title")
      case Column.InvoiceId => min(field("invoice_id")).as("invoice_id")
      case Column.InvoiceNumber => min(field("invoice_number")).as("invoice_number")
    }
  }

  private def makeBaseSql(stripeAccountId: String, liveMode: Boolean, params: Params): CommonTableExpression[?] = {
    val accountsReceivable = DSL.inline(JournalEntry.Account.AccountsReceivable.name)

    val whereClause: Seq[Condition] = Seq(
      Some(field("stripe_account_id") === stripeAccountId),
      Some(field("live_mode") === liveMode),
      Some(field("occurred_at") <= params.exclusiveUpUntil),
      Some(field("settlement_currency") === params.currency),
      Some(accountsReceivable.in(field("debit"), field("credit"))),
      params.customerId.map { customerId => field("customer_id") === customerId }
    ).flatten

    val settlementAmount = field("settlement_amount", classOf[java.lang.Long])
    val amount = sum(
      when(field("debit") === accountsReceivable, settlementAmount)
        .otherwise(0L)
        .add(when(field("credit") === accountsReceivable, settlementAmount.neg()).otherwise(0L))
    )

    val mappedJournalEntries = getMappedJournalEntries()

    val entries = name("entries").as(
      `with`(mappedJournalEntries)
        .select(
          field("transaction_id"),
          field("customer_id"),
          min(field("invoice_id")).as("invoice_id"),
          field(
            "EXTRACT(DAY FROM ({0} - {1}))::INTEGER",
            classOf[Integer],
            DSL.`val`(params.exclusiveUpUntil),
            min(field("occurred_at"))
          ).as("days_outstanding"),
          min(field("occurred_at")).as("occurred_at"),
          amount.as("amount")
        )
        .from(mappedJournalEntries)
        .where(whereClause.asJava)
        .groupBy(field("settlement_currency"), field("customer_id"), field("transaction_id"))
        .having(amount.ne(java.math.BigDecimal.ZERO))
    )

    val e = entries.as("e")
    def eField(columnName: String): Field[Any] = field(name("e", columnName), classOf[Any])
    val eAmount = field(name("e", "amount"), classOf[java.lang.Long])
    val daysOutstanding = field("days_outstanding", classOf[Integer])

    val bucketed = name("bucketed").as(
      `with`(entries)
        .select(
          eField("transaction_id"),
          eField("customer_id"),
          eField("invoice_id"),
          eField("occurred_at"),
          STRIPE_CUSTOMER.NAME.as("customer_name"),
          STRIPE_CUSTOMER.EMAIL.as("customer_email"),
          STRIPE_INVOICE.NUMBER.as("invoice_number"),
          TRANSACTION.TITLE.as("transaction_title"),
          when(daysOutstanding <= 0, eAmount).otherwise(0L).as("not_due"),
          when((daysOutstanding > 0).and(daysOutstanding <= 30), eAmount).otherwise(0L).as("days_30"),
          when((daysOutstanding > 30).and(daysOutstanding <= 60), eAmount).otherwise(0L).as("days_60"),
          when((daysOutstanding > 60).and(daysOutstanding <= 90), eAmount).otherwise(0L).as("days_90"),
          when((daysOutstanding > 90).and(daysOutstanding <= 120), eAmount).otherwise(0L).as("days_120"),
          when(daysOutstanding > 120, eAmount).otherwise(0L).as("days_120_plus"),
          eAmount.as("total")
        )
        .from(e)
        .leftJoin(STRIPE_CUSTOMER).on(eField("customer_id") === STRIPE_CUSTOMER.ID)
        .leftJoin(TRANSACTION).on(eField("transaction_id") === TRANSACTION.ID)
        .leftJoin(STRIPE_INVOICE).on(eField("invoice_id") === STRIPE_INVOICE.ID)
    )

    val groupBys = makeGroupByClause(params)
    val groupSelect = `with`(bucketed)
      .select(makeSelectedColumns(params).asJava)
      .from(bucketed)

    name("groups").as(
      if (groupBys.isEmpty) { groupSelect } else { groupSelect.groupBy(groupBys.asJava) }
    )
  }

  private def getResultColumns(params: Params): Seq[ResultColumn] = {
    params.columns.map { column =>
      ResultColumn(
        id = column,
        tpe = column match {
          case Column.Date => ColumnType.Date
          case Column.NotDue => ColumnType.Amount
          case Column.Days30 => ColumnType.Amount
          case Column.Days60 => ColumnType.Amount
          case Column.Days90 => ColumnType.Amount
          case Column.Days120 => ColumnType.Amount
          case Column.Days120Plus => ColumnType.Amount
          case Column.Total => ColumnType.Amount
          case Column.OccurredAt => ColumnType.Date
          case Column.CustomerId => ColumnType.String
          case Column.CustomerName => ColumnType.String
          case Column.CustomerEmail => ColumnType.String
          case Column.TransactionId => ColumnType.String
          case Column.TransactionTitle => ColumnType.String
          case Column.InvoiceId => ColumnType.String
          case Column.InvoiceNumber => ColumnType.String
        },
      )
    }
  }

  def count(stripeAccountId: String, liveMode: Boolean, params: Params): Future[Long] = {
    db
      .run {
        val groups = makeBaseSql(stripeAccountId, liveMode, params)
        toSqlActionBuilder(
          `with`(groups)
            .select(DSL.count(asterisk()))
            .from(groups)
        ).as[Long]

      }
      .map(_.headOption.getOrElse(0L))
  }

  private def makeListSql(stripeAccountId: String, liveMode: Boolean, params: Params): SelectSeekStepN[?] = {
    val groups = makeBaseSql(stripeAccountId, liveMode, params)

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
}

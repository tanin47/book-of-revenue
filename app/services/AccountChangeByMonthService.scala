package services

import database.models.JournalEntry
import database.services.JournalEntryService
import database.services.JournalEntryService.{ColumnType, SortDirection, getMappedJournalEntries}
import framework.Helpers.{escapeCsv, formatCsvValue}
import framework.Jooq.*
import framework.{Instant, Jsonable, PeriodColumn}
import jooq.generated.public.Tables.TRANSACTION
import org.jooq.impl.DSL
import org.jooq.impl.DSL.*
import org.jooq.scalaextensions.Conversions.*
import org.jooq.{CommonTableExpression, Condition, Field, SelectSeekStepN, SortField, SortOrder}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import process.Helpers.generatePeriods
import slick.jdbc.{GetResult, JdbcProfile}

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.SeqHasAsJava
import scala.language.implicitConversions

object AccountChangeByMonthService {
  enum Column extends Enum[Column] {
    case
    TransactionId,
    TransactionTitle,
    TransactionType,
    TransactionDate,
    TransactionStatus,
    TransactionValue
  }

  case class Sort(column: Column | PeriodColumn, direction: SortDirection)
  enum GroupBy extends Enum[GroupBy] {
    case Transaction
  }

  case class Params(
    periodStart: Instant,
    periodEnd: Instant,
    currency: String,
    groupBy: GroupBy,
    accounts: Seq[JournalEntry.Account],
    customerId: String,
    sorts: Seq[Sort],
  )

  case class ResultColumn(
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
class AccountChangeByMonthService @Inject()(
  val dbConfigProvider: DatabaseConfigProvider,
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {

  import AccountChangeByMonthService.*
  import framework.PostgresProfile.api.*

  def makeBaseSql(stripeAccountId: String, liveMode: Boolean, params: Params): CommonTableExpression[?] = {
    val (creditAccounts, debitAccounts) = params.accounts.partition(_.isCredit())
    val accountNames = params.accounts.map(_.name)

    def whereClause(isCredit: Boolean): Seq[Condition] = Seq(
      field("stripe_account_id") === stripeAccountId,
      field("live_mode") === liveMode,
      field("settlement_currency") === params.currency,
      field("accounting_period") >= params.periodStart,
      field("accounting_period") <= params.periodEnd,
      field("customer_id") === params.customerId,
      if (isCredit) {
        field("credit").in(accountNames.asJava)
      } else {
        field("debit").in(accountNames.asJava)
      }
    )

    val settlementAmount = field("settlement_amount", classOf[java.lang.Long])
    val periods = generatePeriods(params.periodStart, params.periodEnd.plusMillis(1))

    val mappedJournalEntries = getMappedJournalEntries()

    val creditNetChanges = name("credit_net_changes").as(
      `with`(mappedJournalEntries)
        .select(
          asterisk(),
          field("credit").as("account"),
          when(field("credit").in(creditAccounts.map(_.name).asJava), settlementAmount)
            .otherwise(settlementAmount.neg())
            .as("net_settlement_change")
        )
        .from(mappedJournalEntries)
        .where(whereClause(isCredit = true).asJava)
    )

    val debitNetChanges = name("debit_net_changes").as(
      `with`(mappedJournalEntries)
        .select(
          asterisk(),
          field("debit").as("account"),
          when(field("debit").in(debitAccounts.map(_.name).asJava), settlementAmount)
            .otherwise(settlementAmount.neg())
            .as("net_settlement_change")
        )
        .from(mappedJournalEntries)
        .where(whereClause(isCredit = false).asJava)
    )

    val netChanges = name("net_changes").as(
      `with`(creditNetChanges, debitNetChanges)
        .select(asterisk()).from(creditNetChanges)
        .unionAll(select(asterisk()).from(debitNetChanges))
    )

    val periodColumns = periods.map { period =>
      sum(
        when(field("accounting_period", classOf[Instant]).eq(period.startedAt), field("net_settlement_change", classOf[java.lang.Long]))
          .otherwise(DSL.inline(0L))
      ).as(PeriodColumn(period.startedAt.toEpochMilli).name)
    }

    val unfilteredGroups = name("unfiltered_groups").as(
      `with`(netChanges)
        .select((Seq(field("transaction_id")) ++ periodColumns).asJava)
        .from(netChanges)
        .groupBy(field("transaction_id"))
    )

    val periodColumnConditions = periods.map { period =>
      field(quotedName(PeriodColumn(period.startedAt.toEpochMilli).name)) !== 0
    }

    val filteredGroups = name("filtered_groups").as(
      `with`(unfilteredGroups)
        .select(asterisk())
        .from(unfilteredGroups)
        .where(or(periodColumnConditions.asJava))
    )

    val t = TRANSACTION.as("t")
    val g = filteredGroups.as("g")

    name("groups").as(
      `with`(filteredGroups)
        .select(
          t.ID.as("transaction_id"),
          t.TYPE.as("transaction_type"),
          t.TITLE.as("transaction_title"),
          t.STARTED_AT.as("transaction_date"),
          t.STATUS.as("transaction_status"),
          t.SETTLEMENT_TOTAL_VALUE.as("transaction_value"),
          g.asterisk()
        )
        .from(t)
        .leftJoin(g).on(t.ID === field(name("g", "transaction_id"), classOf[String]))
        .where(t.CUSTOMER_ID === params.customerId)
    )
  }

  def count(
    stripeAccountId: String,
    liveMode: Boolean,
    params: Params
  ): Future[Long] = {
    db.run {
      val groups = makeBaseSql(stripeAccountId, liveMode, params)

      toSqlActionBuilder(
        `with`(groups)
          .select(DSL.count(asterisk()))
          .from(groups)
      ).as[Long]
        .map(_.headOption.getOrElse(0L))
    }
  }

  private def makeOrderByClause(sorts: Seq[Sort]): Seq[SortField[?]] = {
    if (sorts.isEmpty) {
      return Seq(field("transaction_date").desc)
    }

    sorts.map { sort =>
      val columnName = sort.column match {
        case e: PeriodColumn => e.name
        case Column.TransactionId => "transaction_id"
        case Column.TransactionType => "transaction_type"
        case Column.TransactionTitle => "transaction_title"
        case Column.TransactionValue => "transaction_value"
        case Column.TransactionStatus => "transaction_status"
        case Column.TransactionDate => "transaction_date"
      }

      field(quotedName(columnName)).sort(SortOrder.valueOf(sort.direction.toString.toUpperCase)).nullsLast()
    }
  }

  private def getResultColumns(params: Params): Seq[ResultColumn] = {
    val periods = generatePeriods(params.periodStart, params.periodEnd.plusMillis(1))

    val columns = (
      Seq(
        Column.TransactionId,
        Column.TransactionTitle,
        Column.TransactionValue,
        Column.TransactionType,
        Column.TransactionStatus,
        Column.TransactionDate,
      ) ++ periods.map { period =>
        PeriodColumn(period.startedAt.toEpochMilli)
      }
    ).asInstanceOf[Seq[Column | PeriodColumn]]

    columns.map { column =>
      ResultColumn(
        id = column,
        tpe = column match {
          case _: PeriodColumn => ColumnType.DeltaAmount
          case Column.TransactionId => ColumnType.String
          case Column.TransactionTitle => ColumnType.String
          case Column.TransactionType => ColumnType.String
          case Column.TransactionValue => ColumnType.Amount
          case Column.TransactionStatus => ColumnType.String
          case Column.TransactionDate => ColumnType.Timestamp
        }
      )
    }
  }

  private def makeSelectedColumns(resultColumns: Seq[ResultColumn]): Seq[Field[?]] = {
    resultColumns.map { col =>
      col.id match {
        case p: PeriodColumn => field(quotedName(p.name))
        case Column.TransactionId => field("transaction_id")
        case Column.TransactionTitle => field("transaction_title")
        case Column.TransactionType => field("transaction_type")
        case Column.TransactionValue => field("transaction_value")
        case Column.TransactionStatus => field("transaction_status")
        case Column.TransactionDate => field("transaction_date")
      }
    }
  }

  private def makeListSql(stripeAccountId: String, liveMode: Boolean, params: Params, resultColumns: Seq[ResultColumn]): SelectSeekStepN[?] = {
    val groups = makeBaseSql(stripeAccountId, liveMode, params)

    `with`(groups)
      .select(makeSelectedColumns(resultColumns).asJava)
      .from(groups)
      .orderBy(makeOrderByClause(params.sorts).asJava)
  }

  def get(
    stripeAccountId: String,
    liveMode: Boolean,
    params: Params,
    offset: Int,
    limit: Int
  ): Future[Result] = {
    val resultColumns = getResultColumns(params)
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns)

    db.run {
      val list = makeListSql(stripeAccountId, liveMode, params, resultColumns)

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

  def exportToCsv(stripeAccountId: String, liveMode: Boolean, params: Params): Future[File] = {
    val destinationFile = Files.createTempFile("transaction", ".csv").toFile
    val resultColumns = getResultColumns(params).toArray
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns.toList)

    val writer = new BufferedWriter(new FileWriter(destinationFile, StandardCharsets.UTF_8))
    writer.write(resultColumns.map { column => escapeCsv(column.id.toString) }.mkString(","))
    writer.newLine()

    db
      .stream {
        val list = makeListSql(stripeAccountId, liveMode, params, resultColumns.toList)

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
      .map { _ => destinationFile }
  }
}

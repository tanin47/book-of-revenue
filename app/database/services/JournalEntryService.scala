package database.services

import database.models.{RevRecTransaction, JournalEntry, JournalEntryTable}
import framework.{Instant, Jsonable, PlayConfig}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import slick.jdbc.{GetResult, JdbcProfile, PositionedResult, SQLActionBuilder}
import slick.lifted.TableQuery

import java.time.OffsetDateTime
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

object JournalEntryService {
  case class CreateData(
    accountingPeriod: Instant,
    debit: JournalEntry.Account,
    credit: JournalEntry.Account,
    amount: Long,
    currency: String,
    occurredAt: Instant,
    event: JournalEntry.Event,
    invoiceId: Option[String],
    invoiceLineItemId: Option[String],
    transactionId: String,
    createdAt: Instant
  )

  case class MrrEntry(
    startedAt: Instant,
    amount: Long,
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "startedAt" -> startedAt.toEpochMilli,
      "amount" -> amount
    )
  }

  enum Column extends Enum[Column] {
    case AccountingPeriod, Debit, Credit, Amount, Currency, OccurredAt, Event, InvoiceId, LineItemId
  }
  enum ColumnType extends Enum[ColumnType] {
    case String, Amount, Number, Date, Timestamp, Period, Percentage, DeltaAmount
  }
  enum SortDirection extends Enum[SortDirection] {
    case Asc, Desc
  }
  case class Sort(column: Column, direction: SortDirection)
  enum GroupBy extends Enum[GroupBy] {
    case Product, Customer, Invoice, LineItem
  }
  case class Params(
    periodStart: Long,
    periodEnd: Long,
    groupBy: Option[GroupBy],
    columns: Seq[Column]
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

  def getValue(columnType: ColumnType, value: PositionedResult): Option[Any] = {
    columnType match {
      case ColumnType.Date | ColumnType.Timestamp | ColumnType.Period => Option(value.skip.rs.getObject(value.currentPos, classOf[OffsetDateTime])).map(_.toInstant.toEpochMilli)
      case ColumnType.Percentage => Some(value.nextDouble())
      case ColumnType.Number | ColumnType.Amount | ColumnType.DeltaAmount => Some(value.nextLong())
      case ColumnType.String => Some(value.nextString())
    }
  }

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
class JournalEntryService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import JournalEntryService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[JournalEntryTable] = TableQuery[JournalEntryTable]

  def getCreateAction(items: Seq[JournalEntry]): DBIOAction[_, NoStream, Effect.Write] = {
    query ++= items
  }

  def getDeleteByTransactionAction(transactionId: String, revRecTransactionType: RevRecTransaction.Type): DBIOAction[_, NoStream, Effect.Write] = {
    query.filter { q => q.revRecTransactionId === transactionId && q.revRecTransactionType === revRecTransactionType }.delete
  }

  def getFirst(): Future[Option[JournalEntry]] = {
    db.run {
      query
        .sortBy(_.accountingPeriod.asc)
        .take(1)
        .result
        .headOption
    }
  }

  def getAll(): Future[Seq[JournalEntry]] = {
    db.run {
      query.result
    }
  }

  private[this] def makeGroupByClause(params: Params): SQLActionBuilder = {
    val baseGroupKey = sql"GROUP BY currency, accounting_period, debit, credit"
    val extraGroupKey = params.groupBy match {
      case Some(GroupBy.Product) => sql", product_id"
      case Some(GroupBy.Customer) => sql", customer_id"
      case Some(GroupBy.Invoice) => sql", invoice_id"
      case Some(GroupBy.LineItem) => sql", invoice_line_item_id"
      case None => sql""
    }

    makeSql(baseGroupKey, extraGroupKey)
  }

  private[this] def makeOrderByClause(sorts: Seq[Sort]): SQLActionBuilder = {
    if (sorts.isEmpty) {
      return sql"ORDER BY accounting_period ASC, debit ASC, credit ASC, currency ASC"
    }

    val sortClauses = sorts.map { sort =>
      val name = sort.column match {
        case Column.AccountingPeriod => "accounting_period"
        case Column.Debit => "debit"
        case Column.Credit => "credit"
        case Column.Amount => "amount"
        case Column.Currency => "currency"
        case Column.OccurredAt => "occurred_at"
        case Column.Event => "event"
        case Column.InvoiceId => "invoice_id"
        case Column.LineItemId => "invoice_line_item_id"
      }

      sql"#${name} #${sort.direction.toString.toUpperCase}"
    }

    makeSql(sql"ORDER BY ", joinSqls(sortClauses, sql", "))
  }

  private[this] def makeSelectedColumns(params: Params): SQLActionBuilder = {
    joinSqls(
      params.columns.map {
        case Column.AccountingPeriod => sql"accounting_period"
        case Column.Debit => sql"debit"
        case Column.Credit => sql"credit"
        case Column.Amount => sql"SUM(amount) AS amount"
        case Column.Currency => sql"currency"
        case Column.InvoiceId => if (params.groupBy.contains(GroupBy.Invoice)) {
          sql"invoice_id"
        } else {
          sql"MIN(invoice_id) AS invoice_id"
        }
        case Column.LineItemId => sql"invoice_line_item_id"
        case _ => throw new IllegalArgumentException(s"Invalid column: $params")
      },
      sql", "
    )
  }

  def deleteByRevRecTransactionId(transactionId: String): Future[Unit] = {
    db
      .run { query.filter(_.revRecTransactionId === transactionId).delete }
      .map { _ => () }
  }

  def deleteStaleJournalEntries(): Future[Unit] = {
    db.run {
      sqlu"""
        WITH
        transaction_ids AS (
          SELECT DISTINCT rev_rec_transaction_id FROM journal_entry
        )

        DELETE FROM journal_entry
        WHERE rev_rec_transaction_id IN (
          SELECT j.rev_rec_transaction_id
          FROM transaction_ids j LEFT JOIN rev_rec_transaction t ON t.id = j.rev_rec_transaction_id
          WHERE t.id IS NULL
        )
      """
    }
      .map { _ => () }
  }

  private[this] def getResultColumns(params: Params): Seq[ResultColumn] = {
    params.columns.map { column =>
      ResultColumn(
        id = column,
        tpe = column match {
          case Column.AccountingPeriod => ColumnType.Date
          case Column.Debit => ColumnType.String
          case Column.Credit => ColumnType.String
          case Column.Amount => ColumnType.Amount
          case Column.Currency => ColumnType.String
          case Column.InvoiceId => ColumnType.String
          case Column.LineItemId => ColumnType.String
          case _ => throw new IllegalArgumentException(s"Invalid column: $params")
        },
        maxCharacterLength = 0
      )
    }
  }

  def getFirstAccountingPeriod(stripeAccountId: String, liveMode: Boolean): Future[Option[Instant]] = {
    db.run {
      sql"""
        select
          MIN(accounting_period) AS first_accounting_period
        FROM journal_entry
        WHERE stripe_account_id = $stripeAccountId AND live_mode = $liveMode
      """
        .as[Option[Instant]]
    }
      .map(_.headOption.flatten)
  }

  def count(params: Params): Future[Long] = {
    db
      .run {
        makeSql(
          sql"""
            WITH groups AS (
              SELECT 1 FROM journal_entry
              WHERE
                accounting_period >= ${Instant.ofEpochMilli(params.periodStart)}
                AND accounting_period <= ${Instant.ofEpochMilli(params.periodEnd)}
          """,
          makeGroupByClause(params),
          sql"""
            )

            SELECT COUNT(*) FROM groups
          """,
        ).as[Long]
      }
      .map(_.headOption.getOrElse(0L))
  }


  def get(params: Params, sorts: Seq[Sort], offset: Int, limit: Int): Future[Result] = {
    val resultColumns = getResultColumns(params)
    implicit val getResult: GetResult[Seq[Option[Any]]] = makeGetResult(resultColumns)
    db
      .run {
        makeSql(
          sql"SELECT",
          makeSelectedColumns(params),
          sql"""
            FROM journal_entry
            WHERE
              accounting_period >= ${Instant.ofEpochMilli(params.periodStart)}
              AND accounting_period <= ${Instant.ofEpochMilli(params.periodEnd)}
          """,
          makeGroupByClause(params),
          makeOrderByClause(sorts),
          sql"LIMIT $limit OFFSET $offset"
        ).as[Seq[Option[Any]]]
      }
      .map { rows =>
        Result(resultColumns, rows.toList)
      }
  }

  def getByRevRecTransactionId(
    stripeAccountId: String,
    liveMode: Boolean,
    transactionId: String,
    lineItemId: Option[String]
  ): Future[Seq[JournalEntry]] = {
    db.run {
      query
        .filter { q =>
          val lineItemIdCond = lineItemId match {
            case Some(lineItemId) => q.invoiceLineItemId === lineItemId
            case None => LiteralColumn(true).?
          }
          q.revRecTransactionId === transactionId &&
            lineItemIdCond &&
            q.stripeAccountId === stripeAccountId &&
            q.liveMode === liveMode
        }
        .result
    }
  }

  def getAllCurrencies(stripeAccountId: String, liveMode: Boolean): Future[Seq[String]] = {
    db.run {
      sql"""
        SELECT DISTINCT settlement_currency FROM journal_entry ORDER BY settlement_currency ASC
      """.as[String]
    }
  }
}

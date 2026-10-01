package services

import database.models.JournalEntry
import database.services.JournalEntryService.getMappedJournalEntries
import framework.Jooq.*
import framework.{Instant, Jsonable}
import org.jooq.impl.DSL.*
import org.jooq.scalaextensions.Conversions.*
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import process.Helpers.generatePeriods
import slick.jdbc.JdbcProfile

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

object MonthlyArpaChartService {
  case class DataPoint(
    period: Instant,
    value: Long
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "period" -> period.toEpochMilli,
      "value" -> value
    )
  }
}

@Singleton
class MonthlyArpaChartService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import MonthlyArpaChartService.*
  import framework.PostgresProfile.api.*

  def get(
    stripeAccountId: String,
    liveMode: Boolean,
    currency: String,
    periodStart: Instant,
    periodEnd: Instant
  ): Future[Seq[DataPoint]] = {
    db.run {
      val mappedJournalEntries = getMappedJournalEntries()

      val entries = name("entries").as(
        `with`(mappedJournalEntries)
          .select(
            mappedJournalEntries.field("accounting_period"),
            mappedJournalEntries.field("customer_id"),
            sum(
              when(mappedJournalEntries.field("debit", classOf[String]).eq(JournalEntry.Account.Revenue.name), mappedJournalEntries.field("settlement_amount", classOf[java.lang.Long]).neg())
                .otherwise(0L)
                .add(when(mappedJournalEntries.field("credit", classOf[String]).eq(JournalEntry.Account.Revenue.name), mappedJournalEntries.field("settlement_amount", classOf[java.lang.Long])).otherwise(0L))
            ).as("net_revenue")
          )
          .from(mappedJournalEntries)
          .where(
            mappedJournalEntries.field("stripe_account_id", classOf[String]) === stripeAccountId,
            mappedJournalEntries.field("live_mode", classOf[Boolean]) === liveMode,
            mappedJournalEntries.field("accounting_period", classOf[Instant]) >= periodStart,
            mappedJournalEntries.field("accounting_period", classOf[Instant]) <= periodEnd,
            mappedJournalEntries.field("settlement_currency", classOf[String]) === currency
          )
          .groupBy(
            mappedJournalEntries.field("accounting_period"),
            mappedJournalEntries.field("customer_id")
          )
      )
      val netRevenue = entries.field("net_revenue", classOf[java.lang.Long])

      toSqlActionBuilder(
        `with`(entries)
          .select(
            entries.field("accounting_period"),
            sum(netRevenue).as("net_revenue"),
            count(entries.field("customer_id")).as("customer_count")
          )
          .from(entries)
          .where(netRevenue.gt(0L))
          .groupBy(entries.field("accounting_period"))
          .orderBy(entries.field("accounting_period").asc())
      ).as[(Timestamp, Long, Long)]
    }
      .map { items =>
        items.map { case (period, netRevenue, customerCount) => DataPoint(period = period.toInstant, value = netRevenue / customerCount) }
      }
      .map { items =>
        val pointByPeriod = items.groupBy(_.period).view.mapValues(_.head).toMap

        generatePeriods(periodStart, periodEnd.plusMillis(1)).map { period =>
          pointByPeriod.getOrElse(period.startedAt, DataPoint(period = period.startedAt, value = 0))
        }
      }
  }
}

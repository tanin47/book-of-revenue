package services

import framework.{Instant, Jsonable}
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
      sql"""
        WITH entries AS (
          SELECT
            accounting_period,
            customer_id,
            SUM(
              (CASE WHEN debit = 'Revenue' THEN -settlement_amount ELSE 0 END)
              + (CASE WHEN credit = 'Revenue' THEN settlement_amount ELSE 0 END)
            ) AS net_revenue
          FROM journal_entry
          WHERE
            stripe_account_id = $stripeAccountId
            AND live_mode = $liveMode
            AND accounting_period >= $periodStart
            AND accounting_period <= $periodEnd
            AND settlement_currency = $currency
          GROUP BY accounting_period, customer_id
        )

        SELECT
          accounting_period,
          SUM(net_revenue) AS net_revenue,
          COUNT(customer_id) AS customer_count
        FROM entries
        WHERE net_revenue > 0
        GROUP BY accounting_period
        ORDER BY accounting_period ASC
      """.as[(Timestamp, Long, Long)]
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

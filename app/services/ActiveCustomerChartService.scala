package services

import framework.{Instant, Jsonable}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import process.Helpers.generatePeriods
import services.NetRevenueService.{Column, GroupBy, ShowOnly, Sort}
import slick.jdbc.JdbcProfile

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

object ActiveCustomerChartService {
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
class ActiveCustomerChartService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  netRevenueService: NetRevenueService,
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import ActiveCustomerChartService.*
  import framework.PostgresProfile.api.*

  def get(stripeAccountId: String, liveMode: Boolean, currency: String, periodStart: Instant, periodEnd: Instant): Future[Seq[DataPoint]] = {
    db.run {
      makeSql(
        netRevenueService.makeBaseWithSql(
          stripeAccountId = stripeAccountId,
          liveMode = liveMode,
          params = NetRevenueService.Params(
            periodStart = periodStart,
            periodEnd = periodEnd,
            currency = currency,
            groupBy = Some(NetRevenueService.GroupBy.Customer),
            showOnly = Some(NetRevenueService.ShowOnly.NetRevenue),
            productId = None,
            customerId = None,
            transactionId = None,
            columns = Seq.empty,
            sorts = Seq.empty
          )
        ),
        sql"""
          SELECT
            accounting_period,
            COUNT(*) AS customer_count
          FROM groups
          GROUP BY accounting_period
          ORDER BY accounting_period ASC
        """
      ).as[(Instant, Long)]
    }
      .map { items =>
        items.map { case (period, customerCount) => DataPoint(period = period, value = customerCount) }
      }
      .map { items =>
        val pointByPeriod = items.groupBy(_.period).view.mapValues(_.head).toMap

        generatePeriods(periodStart, periodEnd.plusMillis(1)).map { period =>
          pointByPeriod.getOrElse(period.startedAt, DataPoint(period = period.startedAt, value = 0))
        }
      }
  }
}

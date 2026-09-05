package services

import framework.{Instant, Jsonable}
import play.api.libs.json.{JsObject, Json}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

object ArAgingChartService {
  case class Result(
    exclusiveUpUntil: Instant,
    currency: String,
    notDue: Long,
    days30: Long,
    days60: Long,
    days90: Long,
    days120: Long,
    days120Plus: Long
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "exclusiveUpUntil" -> exclusiveUpUntil.toEpochMilli,
      "notDue" -> notDue,
      "days30" -> days30,
      "days60" -> days60,
      "days90" -> days90,
      "days120" -> days120,
      "days120Plus" -> days120Plus
    )
  }
}

@Singleton
class ArAgingChartService @Inject() (
  arAgingService: ArAgingService,
)(implicit ec: ExecutionContext) {
  import ArAgingChartService.*

  def get(stripeAccountId: String, liveMode: Boolean, currency: String, exclusiveUpUntil: Instant): Future[Option[Result]] = {
    arAgingService.get(
      stripeAccountId = stripeAccountId,
      liveMode = liveMode,
      params = ArAgingService.Params(
        exclusiveUpUntil = exclusiveUpUntil,
        groupBy = ArAgingService.GroupBy.Summary,
        currency = currency,
        customerId = None,
        columns = Seq(
          ArAgingService.Column.NotDue,
          ArAgingService.Column.Days30,
          ArAgingService.Column.Days60,
          ArAgingService.Column.Days90,
          ArAgingService.Column.Days120,
          ArAgingService.Column.Days120Plus,
          ArAgingService.Column.Total,
          ArAgingService.Column.Date,
        ),
        sorts = Seq.empty,
      ),
      offset = 0,
      limit = 10000
    ).map { result =>
      result.rows.headOption.map { row =>
        Result(
          exclusiveUpUntil = exclusiveUpUntil,
          currency = currency,
          notDue = row.head.asInstanceOf[Option[Long]].getOrElse(0L),
          days30 = row.apply(1).asInstanceOf[Option[Long]].getOrElse(0L),
          days60 = row.apply(2).asInstanceOf[Option[Long]].getOrElse(0L),
          days90 = row.apply(3).asInstanceOf[Option[Long]].getOrElse(0L),
          days120 = row.apply(4).asInstanceOf[Option[Long]].getOrElse(0L),
          days120Plus = row.apply(5).asInstanceOf[Option[Long]].getOrElse(0L)
        )
      }
    }
  }
}

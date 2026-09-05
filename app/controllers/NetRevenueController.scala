package controllers

import database.services.ExportedFileService
import database.services.JournalEntryService.SortDirection
import framework.*
import framework.Helpers.enumForm
import givers.form.Form
import givers.form.Mappings.*
import play.api.libs.json.{JsNull, JsNumber, JsString, JsValue, Json}
import play.api.mvc.{Action, AnyContent}
import services.NetRevenueService

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

object NetRevenueController {

  case class LoadNetRevenueData(
    params: NetRevenueService.Params,
    offset: Option[Int],
  )

  val LOAD_NET_REVENUE_FORM: Form[LoadNetRevenueData] = Form(
    "validation.get",
    LoadNetRevenueData.apply,
    Tuples.to[LoadNetRevenueData],
    "params" -> obj(
      NetRevenueService.Params.apply,
      Tuples.to[NetRevenueService.Params],
      "periodStart" -> Instant.form,
      "periodEnd" -> Instant.form,
      "currency" -> text(allowEmpty = false),
      "groupBy" -> opt(enumForm[NetRevenueService.GroupBy]),
      "showOnly" -> opt(enumForm[NetRevenueService.ShowOnly]),
      "productId" -> opt(text(allowEmpty = false)),
      "customerId" -> opt(text(allowEmpty = false)),
      "transactionId" -> opt(text(allowEmpty = false)),
      "columns" -> seq(enumForm[NetRevenueService.Column]),
      "sorts" -> seq(obj(
        NetRevenueService.Sort.apply,
        Tuples.to[NetRevenueService.Sort],
        "columnId" -> enumForm[NetRevenueService.Column],
        "direction" -> enumForm[SortDirection]
      )),
    ),
    "offset" -> opt(number(min = 0)),
  )
}

@Singleton
class NetRevenueController @Inject() (
  netRevenueService: NetRevenueService,
  exportedFileService: ExportedFileService,
  cc: ControllerComponents
)(implicit ec: ExecutionContext) extends BaseController(cc) {
  import NetRevenueController.*

  def index(): Action[AnyContent] = async() { implicit req =>
    Future(Ok(views.html.dashboard.netRevenue()))
  }

  def load(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_NET_REVENUE_FORM.bindFromRequest().get

    for {
      totalNumberOfRows <- netRevenueService.count(req.stripeAccountId, req.liveMode, data.params)
      result <- netRevenueService.get(req.stripeAccountId, req.liveMode, data.params, data.offset.getOrElse(0), 200)
    } yield {
      Ok(Json.obj(
        "totalNumberOfRows" -> totalNumberOfRows,
        "columns" -> result.columns.map(_.toJson()),
        "rows" -> result.rows.map { row =>
          row.map {
            case None => JsNull
            case Some(v: String) => JsString(v)
            case Some(v: Long) => JsNumber(v)
            case v => throw new RuntimeException(s"Unexpected value: $v")
          }
        }
      ))
    }
  }

  def exportCsv(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_NET_REVENUE_FORM.bindFromRequest().get

    for {
      tmpFile <- netRevenueService.exportToCsv(req.stripeAccountId, req.liveMode, data.params)
      exportedFile <- exportedFileService.create(req.stripeAccountId, req.liveMode, "net-revenue", tmpFile)
    } yield {
      Ok(Json.obj("url" -> routes.ExportedFileController.download(exportedFile.id).url))
    }
  }
}

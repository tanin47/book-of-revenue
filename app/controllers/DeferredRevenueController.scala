package controllers

import database.models.JournalEntry
import database.services.ExportedFileService
import database.services.JournalEntryService.SortDirection
import framework.*
import framework.Helpers.{constantForm, enumForm}
import givers.form.Form
import givers.form.Mappings.*
import play.api.libs.json.*
import play.api.mvc.{Action, AnyContent}
import services.AccountChangeByEventService

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

object DeferredRevenueController {

  case class LoadData(
    params: AccountChangeByEventService.Params,
    offset: Option[Int],
  )

  val LOAD_FORM: Form[LoadData] = Form(
    "validation.get",
    LoadData.apply,
    Tuples.to[LoadData],
    "params" -> obj(
      AccountChangeByEventService.Params.apply,
      Tuples.to[AccountChangeByEventService.Params],
      "periodStart" -> Instant.form,
      "periodEnd" -> Instant.form,
      "currency" -> text(allowEmpty = false),
      "groupBy" -> enumForm[AccountChangeByEventService.GroupBy],
      "showOnly" -> opt(EventColumn.form[AccountChangeByEventService.Column]),
      "productId" -> opt(text(allowEmpty = false)),
      "customerId" -> opt(text(allowEmpty = false)),
      "transactionId" -> opt(text(allowEmpty = false)),
      "account" -> constantForm(JournalEntry.Account.DeferredRevenue),
      "columns" -> seq(enumForm[AccountChangeByEventService.Column]),
      "sorts" -> seq(obj(
        AccountChangeByEventService.Sort.apply,
        Tuples.to[AccountChangeByEventService.Sort],
        "columnId" -> EventColumn.form[AccountChangeByEventService.Column],
        "direction" -> enumForm[SortDirection]
      )),
    ),
    "offset" -> opt(number(min = 0)),
  )
}

@Singleton
class DeferredRevenueController @Inject() (
  accountChangeByEventService: AccountChangeByEventService,
  exportedFileService: ExportedFileService,
  cc: ControllerComponents
)(implicit ec: ExecutionContext) extends BaseController(cc) {
  import DeferredRevenueController.*

  def index(): Action[AnyContent] = async() { implicit req =>
    Future(Ok(views.html.dashboard.deferredRevenue()))
  }

  def load(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_FORM.bindFromRequest().get

    for {
      totalNumberOfRows <- accountChangeByEventService.count(req.stripeAccountId, req.liveMode, data.params)
      result <- accountChangeByEventService.get(req.stripeAccountId, req.liveMode, data.params, data.offset.getOrElse(0), 200)
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
    val data = LOAD_FORM.bindFromRequest().get

    for {
      tmpFile <- accountChangeByEventService.exportToCsv(req.stripeAccountId, req.liveMode, data.params)
      exportedFile <- exportedFileService.create(req.stripeAccountId, req.liveMode, "deferred-revenue-change", tmpFile)
    } yield {
      Ok(Json.obj("url" -> routes.ExportedFileController.download(exportedFile.id).url))
    }
  }
}

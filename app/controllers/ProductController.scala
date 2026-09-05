package controllers

import database.models.JournalEntry
import database.services.ExportedFileService
import database.services.JournalEntryService.SortDirection
import framework.*
import framework.Helpers.{constantForm, enumForm}
import givers.form.Form
import givers.form.Mappings.*
import play.api.libs.json.*
import play.api.mvc.{Action, AnyContent, Result}
import services.{ContractualLiabilityService, MonthlyGrrService, MonthlyNrrService, NetRevenueService}

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

object ProductController {
  case class LoadNetRevenueData(
    params: NetRevenueService.RevenueByMonthParams,
    offset: Option[Int],
  )

  val LOAD_NET_REVENUE_FORM: Form[LoadNetRevenueData] = Form(
    "validation.get",
    LoadNetRevenueData.apply,
    Tuples.to[LoadNetRevenueData],
    "params" -> obj(
      NetRevenueService.RevenueByMonthParams.apply,
      Tuples.to[NetRevenueService.RevenueByMonthParams],
      "keyword" -> text,
      "periodStart" -> Instant.form,
      "periodEnd" -> Instant.form,
      "currency" -> text(allowEmpty = false),
      "groupBy" -> constantForm(NetRevenueService.GroupBy.Product),
      "customerId" -> constantForm[Option[String]](None),
      "sorts" -> seq(obj(
        NetRevenueService.RevenueByMonthSort.apply,
        Tuples.to[NetRevenueService.RevenueByMonthSort],
        "columnId" -> PeriodColumn.form[NetRevenueService.Column],
        "direction" -> enumForm[SortDirection]
      )),
    ),
    "offset" -> opt(number(min = 0)),
  )

  case class LoadContractualLiabilityData(
    params: ContractualLiabilityService.ByMonthParams,
    offset: Option[Int],
  )

  val LOAD_CONTRACTUAL_LIABILITY_FORM: Form[LoadContractualLiabilityData] = Form(
    "validation.get",
    LoadContractualLiabilityData.apply,
    Tuples.to[LoadContractualLiabilityData],
    "params" -> obj(
      ContractualLiabilityService.ByMonthParams.apply,
      Tuples.to[ContractualLiabilityService.ByMonthParams],
      "keyword" -> text,
      "periodStart" -> Instant.form,
      "periodEnd" -> Instant.form,
      "currency" -> text(allowEmpty = false),
      "groupBy" -> constantForm(ContractualLiabilityService.GroupBy.Product),
      "customerId" -> constantForm[Option[String]](None),
      "sorts" -> seq(obj(
        ContractualLiabilityService.ByMonthSort.apply,
        Tuples.to[ContractualLiabilityService.ByMonthSort],
        "columnId" -> PeriodColumn.form[ContractualLiabilityService.Column],
        "direction" -> enumForm[SortDirection]
      )),
    ),
    "offset" -> opt(number(min = 0)),
  )
}

@Singleton
class ProductController @Inject() (
  netRevenueService: NetRevenueService,
  contractualLiabilityService: ContractualLiabilityService,
  exportedFileService: ExportedFileService,
  cc: ControllerComponents
)(implicit ec: ExecutionContext) extends BaseController(cc) {
  import ProductController.*

  def index(): Action[AnyContent] = async() { implicit req =>
    Future(Ok(views.html.product.index()))
  }

  def loadNetRevenue(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_NET_REVENUE_FORM.bindFromRequest().get

    for {
      totalNumberOfRows <- netRevenueService.countRevenueByMonth(req.stripeAccountId, req.liveMode, data.params)
      result <- netRevenueService.getRevenueByMonth(req.stripeAccountId, req.liveMode, data.params, data.offset.getOrElse(0), 200)
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

  def exportNetRevenue(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_NET_REVENUE_FORM.bindFromRequest().get

    for {
      tmpFile <- netRevenueService.exportRevenueByMonthToCsv(req.stripeAccountId, req.liveMode, data.params)
      exportedFile <- exportedFileService.create(req.stripeAccountId, req.liveMode, "customer-net-revenue", tmpFile)
    } yield {
      Ok(Json.obj("url" -> routes.ExportedFileController.download(exportedFile.id).url))
    }
  }

  def deferredRevenue(): Action[AnyContent] = async() { implicit req =>
    Future(Ok(views.html.product.deferredRevenue()))
  }
  def loadDeferredRevenue(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    loadContractualLiabilities(Seq(JournalEntry.Account.DeferredRevenue))
  }
  def exportDeferredRevenue(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    exportContractualLiabilities(Seq(JournalEntry.Account.DeferredRevenue), "deferred-revenue")
  }

  private[this] def loadContractualLiabilities(accounts: Seq[JournalEntry.Account])(implicit req: AuthRequest[_]): Future[Result] = {
    val data = LOAD_CONTRACTUAL_LIABILITY_FORM.bindFromRequest().get

    for {
      totalNumberOfRows <- contractualLiabilityService.countEndingBalanceByMonth(req.stripeAccountId, req.liveMode, data.params, accounts)
      result <- contractualLiabilityService.getEndingBalanceByMonth(req.stripeAccountId, req.liveMode, data.params, accounts, data.offset.getOrElse(0), 200)
    } yield {
      Ok(Json.obj(
        "totalNumberOfRows" -> totalNumberOfRows,
        "columns" -> result.columns.map(_.toJson()),
        "rows" -> result.rows.map { row =>
          row.map {
            case None => JsNull
            case Some(v: String) => JsString(v)
            case Some(v: Long) => JsNumber(v)
            case Some(v: Double) => JsNumber(v)
            case v => throw new RuntimeException(s"Unexpected value: $v")
          }
        }
      ))
    }
  }

  private[this] def exportContractualLiabilities(accounts: Seq[JournalEntry.Account], partFileName: String)(implicit req: AuthRequest[_]): Future[Result] = {
    val data = LOAD_CONTRACTUAL_LIABILITY_FORM.bindFromRequest().get

    for {
      tmpFile <- contractualLiabilityService.exportEndingBalanceByMonthToCsv(req.stripeAccountId, req.liveMode, data.params, accounts)
      exportedFile <- exportedFileService.create(req.stripeAccountId, req.liveMode, s"customer-$partFileName", tmpFile)
    } yield {
      Ok(Json.obj("url" -> routes.ExportedFileController.download(exportedFile.id).url))
    }
  }
}

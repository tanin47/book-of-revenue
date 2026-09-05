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
import services.NetRevenueService.{RevenueByMonthParams, RevenueByMonthSort}

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

object CustomerController {
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
      "groupBy" -> constantForm(NetRevenueService.GroupBy.Customer),
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

  case class LoadMonthlyNrrData(
    params: MonthlyNrrService.CustomerRevenueByMonthParams,
    offset: Option[Int],
  )

  val LOAD_MONTHLY_NRR_FORM: Form[LoadMonthlyNrrData] = Form(
    "validation.get",
    LoadMonthlyNrrData.apply,
    Tuples.to[LoadMonthlyNrrData],
    "params" -> obj(
      MonthlyNrrService.CustomerRevenueByMonthParams.apply,
      Tuples.to[MonthlyNrrService.CustomerRevenueByMonthParams],
      "keyword" -> text,
      "periodStart" -> Instant.form,
      "periodEnd" -> Instant.form,
      "currency" -> text(allowEmpty = false),
      "sorts" -> seq(obj(
        MonthlyNrrService.CustomerRevenueByMonthSort.apply,
        Tuples.to[MonthlyNrrService.CustomerRevenueByMonthSort],
        "columnId" -> PeriodColumn.form[MonthlyNrrService.Column],
        "direction" -> enumForm[SortDirection]
      )),
    ),
    "offset" -> opt(number(min = 0)),
  )

  case class LoadMonthlyGrrData(
    params: MonthlyGrrService.CustomerRevenueByMonthParams,
    offset: Option[Int],
  )

  val LOAD_MONTHLY_GRR_FORM: Form[LoadMonthlyGrrData] = Form(
    "validation.get",
    LoadMonthlyGrrData.apply,
    Tuples.to[LoadMonthlyGrrData],
    "params" -> obj(
      MonthlyGrrService.CustomerRevenueByMonthParams.apply,
      Tuples.to[MonthlyGrrService.CustomerRevenueByMonthParams],
      "keyword" -> text,
      "periodStart" -> Instant.form,
      "periodEnd" -> Instant.form,
      "currency" -> text(allowEmpty = false),
      "sorts" -> seq(obj(
        MonthlyGrrService.CustomerRevenueByMonthSort.apply,
        Tuples.to[MonthlyGrrService.CustomerRevenueByMonthSort],
        "columnId" -> PeriodColumn.form[MonthlyGrrService.Column],
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
      "groupBy" -> constantForm(ContractualLiabilityService.GroupBy.Customer),
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
class CustomerController @Inject() (
  netRevenueService: NetRevenueService,
  monthlyNrrService: MonthlyNrrService,
  monthlyGrrService: MonthlyGrrService,
  contractualLiabilityService: ContractualLiabilityService,
  exportedFileService: ExportedFileService,
  cc: ControllerComponents
)(implicit ec: ExecutionContext) extends BaseController(cc) {
  import CustomerController.*

  def index(): Action[AnyContent] = async() { implicit req =>
    Future(Ok(views.html.customer.index()))
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

  def monthlyNrr(): Action[AnyContent] = async() { implicit req =>
    Future(Ok(views.html.customer.nrr()))
  }

  def loadMonthlyNrr(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_MONTHLY_NRR_FORM.bindFromRequest().get

    for {
      totalNumberOfRows <- monthlyNrrService.countCustomerRevenueByMonth(req.stripeAccountId, req.liveMode, data.params)
      result <- monthlyNrrService.getCustomerRevenueByMonth(req.stripeAccountId, req.liveMode, data.params, data.offset.getOrElse(0), 200)
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

  def exportMonthlyNrr(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_MONTHLY_NRR_FORM.bindFromRequest().get

    for {
      tmpFile <- monthlyNrrService.exportCustomerRevenueByMonthToCsv(req.stripeAccountId, req.liveMode, data.params)
      exportedFile <- exportedFileService.create(req.stripeAccountId, req.liveMode, "customer-nrr", tmpFile)
    } yield {
      Ok(Json.obj("url" -> routes.ExportedFileController.download(exportedFile.id).url))
    }
  }

  def monthlyGrr(): Action[AnyContent] = async() { implicit req =>
    Future(Ok(views.html.customer.grr()))
  }

  def loadMonthlyGrr(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_MONTHLY_GRR_FORM.bindFromRequest().get

    for {
      totalNumberOfRows <- monthlyGrrService.countCustomerRevenueByMonth(req.stripeAccountId, req.liveMode, data.params)
      result <- monthlyGrrService.getCustomerRevenueByMonth(req.stripeAccountId, req.liveMode, data.params, data.offset.getOrElse(0), 200)
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

  def exportMonthlyGrr(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_MONTHLY_GRR_FORM.bindFromRequest().get

    for {
      tmpFile <- monthlyGrrService.exportCustomerRevenueByMonthToCsv(req.stripeAccountId, req.liveMode, data.params)
      exportedFile <- exportedFileService.create(req.stripeAccountId, req.liveMode, "customer-grr", tmpFile)
    } yield {
      Ok(Json.obj("url" -> routes.ExportedFileController.download(exportedFile.id).url))
    }
  }

  def deferredRevenue(): Action[AnyContent] = async() { implicit req =>
    Future(Ok(views.html.customer.deferredRevenue()))
  }
  def loadDeferredRevenue(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    loadContractualLiabilities(Seq(JournalEntry.Account.DeferredRevenue))
  }
  def exportDeferredRevenue(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    exportContractualLiabilities(Seq(JournalEntry.Account.DeferredRevenue), "deferred-revenue")
  }

  private[this] val otherContractualLiabilityAccounts = JournalEntry.Account.values
    .filter { a => a.getAccountCategory() == JournalEntry.AccountCategory.ContractLiability && a != JournalEntry.Account.DeferredRevenue }
    .toList
  def otherContractualLiabilities(): Action[AnyContent] = async() { implicit req =>
    Future(Ok(views.html.customer.otherContractualLiabilities()))
  }
  def loadOtherContractualLiabilities(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    loadContractualLiabilities(otherContractualLiabilityAccounts)
  }
  def exportOtherContractualLiabilities(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    exportContractualLiabilities(otherContractualLiabilityAccounts, "other-contractual-liabilities")
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

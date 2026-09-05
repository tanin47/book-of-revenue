package controllers

import database.models.{Customer, JournalEntry}
import database.services.{CustomerService, ExportedFileService}
import database.services.JournalEntryService.SortDirection
import framework.*
import framework.Helpers.{constantForm, enumForm}
import givers.form.Form
import givers.form.Mappings.*
import play.api.libs.json.*
import play.api.mvc.{Action, AnyContent, Result}
import services.{AccountChangeByMonthService, ContractualLiabilityService, NetRevenueService}

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

object CustomerViewController {
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
      "groupBy" -> constantForm(NetRevenueService.GroupBy.Transaction),
      "customerId" -> text(allowEmpty = false).pipe[Option[String]](Some.apply, _.get),
      "sorts" -> seq(obj(
        NetRevenueService.RevenueByMonthSort.apply,
        Tuples.to[NetRevenueService.RevenueByMonthSort],
        "columnId" -> PeriodColumn.form[NetRevenueService.Column],
        "direction" -> enumForm[SortDirection]
      )),
    ),
    "offset" -> opt(number(min = 0)),
  )


  case class LoadDeferredRevenueBalanceData(
    params: ContractualLiabilityService.ByMonthParams,
    offset: Option[Int],
  )

  val LOAD_DEFERRED_REVENUE_BALANCE_FORM: Form[LoadDeferredRevenueBalanceData] = Form(
    "validation.get",
    LoadDeferredRevenueBalanceData.apply,
    Tuples.to[LoadDeferredRevenueBalanceData],
    "params" -> obj(
      ContractualLiabilityService.ByMonthParams.apply,
      Tuples.to[ContractualLiabilityService.ByMonthParams],
      "keyword" -> text,
      "periodStart" -> Instant.form,
      "periodEnd" -> Instant.form,
      "currency" -> text(allowEmpty = false),
      "groupBy" -> constantForm(ContractualLiabilityService.GroupBy.Transaction),
      "customerId" -> text(allowEmpty = false).pipe[Option[String]](Some.apply, _.get),
      "sorts" -> seq(obj(
        ContractualLiabilityService.ByMonthSort.apply,
        Tuples.to[ContractualLiabilityService.ByMonthSort],
        "columnId" -> PeriodColumn.form[ContractualLiabilityService.Column],
        "direction" -> enumForm[SortDirection]
      )),
    ),
    "offset" -> opt(number(min = 0)),
  )

  case class LoadOtherContractualLiabilityData(
    params: AccountChangeByMonthService.Params,
    offset: Option[Int],
  )

  val LOAD_OTHER_CONTRACTUAL_LIABILITY_FORM: Form[LoadOtherContractualLiabilityData] = Form(
    "validation.get",
    LoadOtherContractualLiabilityData.apply,
    Tuples.to[LoadOtherContractualLiabilityData],
    "params" -> obj(
      AccountChangeByMonthService.Params.apply,
      Tuples.to[services.AccountChangeByMonthService.Params],
      "periodStart" -> Instant.form,
      "periodEnd" -> Instant.form,
      "currency" -> text(allowEmpty = false),
      "groupBy" -> constantForm(AccountChangeByMonthService.GroupBy.Transaction),
      "accounts" -> constantForm(JournalEntry.Account.values.filter { a =>
        a.getAccountCategory() == JournalEntry.AccountCategory.ContractLiability && a != JournalEntry.Account.DeferredRevenue
      }.toList),
      "customerId" -> text(allowEmpty = false),
      "sorts" -> seq(obj(
        AccountChangeByMonthService.Sort.apply,
        Tuples.to[AccountChangeByMonthService.Sort],
        "columnId" -> PeriodColumn.form[AccountChangeByMonthService.Column],
        "direction" -> enumForm[SortDirection]
      )),
    ),
    "offset" -> opt(number(min = 0)),
  )
}

@Singleton
class CustomerViewController @Inject() (
  customerService: CustomerService,
  netRevenueService: NetRevenueService,
  contractualLiabilityService: ContractualLiabilityService,
  exportedFileService: ExportedFileService,
  accountChangeByMonthService: AccountChangeByMonthService,
  cc: ControllerComponents
)(implicit ec: ExecutionContext) extends BaseController(cc) {
  import CustomerViewController.*

  def index(customerId: String): Action[AnyContent] = authenticated() { implicit req =>
    for {
      (customerId, customer) <- getCustomer(customerId)
    } yield {
      Ok(views.html.customerView.index(customerId, customer))
    }
  }

  private[this] def getCustomer(customerId: String)(implicit req: AuthRequest[_]): Future[(Option[String], Option[Customer])] = {
    val sanitizedCustomerId = Some(customerId).filter(_ != "empty")
    for {
      customer <- sanitizedCustomerId match {
        case Some(customerId) => customerService.getById(req.stripeAccountId, req.liveMode, customerId)
        case None => Future(None)
      }
    } yield {
      sanitizedCustomerId -> customer
    }
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

  def deferredRevenue(customerId: String): Action[AnyContent] = authenticated() { implicit req =>
    for {
      (customerId, customer) <- getCustomer(customerId)
    } yield {
      Ok(views.html.customerView.deferredRevenue(customerId, customer))
    }
  }
  def loadDeferredRevenue(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_DEFERRED_REVENUE_BALANCE_FORM.bindFromRequest().get
    val accounts = Seq(JournalEntry.Account.DeferredRevenue)

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
  def exportDeferredRevenue(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_DEFERRED_REVENUE_BALANCE_FORM.bindFromRequest().get
    val accounts = Seq(JournalEntry.Account.DeferredRevenue)

    for {
      tmpFile <- contractualLiabilityService.exportEndingBalanceByMonthToCsv(req.stripeAccountId, req.liveMode, data.params, accounts)
      exportedFile <- exportedFileService.create(req.stripeAccountId, req.liveMode, s"transaction-deferred-revenue-balance", tmpFile)
    } yield {
      Ok(Json.obj("url" -> routes.ExportedFileController.download(exportedFile.id).url))
    }
  }

  def otherContractualLiabilities(customerId: String): Action[AnyContent] = authenticated() { implicit req =>
    for {
      (customerId, customer) <- getCustomer(customerId)
    } yield {
      Ok(views.html.customerView.otherContractualLiabilities(customerId, customer))
    }
  }
  def loadOtherContractualLiabilities(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_OTHER_CONTRACTUAL_LIABILITY_FORM.bindFromRequest().get

    for {
      totalNumberOfRows <- accountChangeByMonthService.count(req.stripeAccountId, req.liveMode, data.params)
      result <- accountChangeByMonthService.get(req.stripeAccountId, req.liveMode, data.params, data.offset.getOrElse(0), 200)
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
  def exportOtherContractualLiabilities(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_OTHER_CONTRACTUAL_LIABILITY_FORM.bindFromRequest().get

    for {
      tmpFile <- accountChangeByMonthService.exportToCsv(req.stripeAccountId, req.liveMode, data.params)
      exportedFile <- exportedFileService.create(req.stripeAccountId, req.liveMode, s"customer-other-contractual-liabilities", tmpFile)
    } yield {
      Ok(Json.obj("url" -> routes.ExportedFileController.download(exportedFile.id).url))
    }
  }
}

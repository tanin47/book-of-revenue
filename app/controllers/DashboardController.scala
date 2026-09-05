package controllers

import background.ProcessTransactionWorker
import database.models.{JournalEntry, RevRecTransaction}
import database.services.JournalEntryService.SortDirection
import database.services.{CustomerService, ExportedFileService, InvoiceLineItemService, RevRecTransactionService}
import framework.Helpers.{enumForm, toMonthEnd}
import framework.*
import givers.form.Form
import givers.form.Mappings.*
import play.api.libs.json.*
import play.api.mvc.AnyContent
import services.*

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

object DashboardController {
  case class LoadOverviewData(
    periodStart: Instant,
    periodEnd: Instant,
    currency: String
  )

  val LOAD_OVERVIEW_FORM: Form[LoadOverviewData] = Form(
    "validation.get",
    LoadOverviewData.apply,
    Tuples.to[LoadOverviewData],
    "periodStart" -> Instant.form,
    "periodEnd" -> Instant.form,
    "currency" -> text(allowEmpty = false),
  )

  case class LoadDebitsAndCreditsData(
    params: DebitsAndCreditsService.Params,
    offset: Option[Int],
  )

  val LOAD_DEBITS_AND_CREDITS_FORM: Form[LoadDebitsAndCreditsData] = Form(
    "validation.get",
    LoadDebitsAndCreditsData.apply,
    Tuples.to[LoadDebitsAndCreditsData],
    "params" -> obj(
      DebitsAndCreditsService.Params.apply,
      Tuples.to[DebitsAndCreditsService.Params],
      "periodStart" -> opt(Instant.form),
      "periodEnd" -> opt(Instant.form),
      "transactionId" -> opt(text),
      "groupBy" -> opt(enumForm[DebitsAndCreditsService.GroupBy]),
      "currency" -> text(allowEmpty = false),
      "lineItemId" -> opt(text(allowEmpty = false)),
      "accounts" -> seq(text),
      "columns" -> seq(enumForm[DebitsAndCreditsService.Column]),
      "sorts" -> seq(obj(
        DebitsAndCreditsService.Sort.apply,
        Tuples.to[DebitsAndCreditsService.Sort],
        "columnId" -> enumForm[DebitsAndCreditsService.Column],
        "direction" -> enumForm[SortDirection]
      )),
    ),
    "offset" -> opt(number(min = 0)),
  )

  case class LoadAccountSummaryData(
    transactionId: String,
    lineItemId: Option[String]
  )

  val LOAD_ACCOUNT_SUMMARY_FORM: Form[LoadAccountSummaryData] = Form(
    "validation.get",
    LoadAccountSummaryData.apply,
    Tuples.to[LoadAccountSummaryData],
    "transactionId" -> text(allowEmpty = false),
    "lineItemId" -> opt(text(allowEmpty = false)),
  )

  case class LoadLineItemsData(transactionId: Option[String])

  val LOAD_LINE_ITEMS_FORM: Form[LoadLineItemsData] = Form(
    "validation.get",
    LoadLineItemsData.apply,
    Tuples.to[LoadLineItemsData],
    "transactionId" -> opt(text(allowEmpty = false)),
  )

  case class LoadIncomeStatementData(
    params: IncomeStatementService.Params,
    offset: Option[Int],
  )

  val LOAD_INCOME_STATEMENT_FORM: Form[LoadIncomeStatementData] = Form(
    "validation.get",
    LoadIncomeStatementData.apply,
    Tuples.to[LoadIncomeStatementData],
    "params" -> obj(
      IncomeStatementService.Params.apply,
      Tuples.to[IncomeStatementService.Params],
      "periodStart" -> opt(Instant.form),
      "periodEnd" -> opt(Instant.form),
      "groupBy" -> opt(enumForm[IncomeStatementService.GroupBy]),
      "currency" -> text(allowEmpty = false),
      "productId" -> opt(text),
      "customerId" -> opt(text),
      "transactionId" -> opt(text),
      "accounts" -> seq(text),
      "columns" -> seq(enumForm[IncomeStatementService.Column]),
      "sorts" -> seq(obj(
        IncomeStatementService.Sort.apply,
        Tuples.to[IncomeStatementService.Sort],
        "columnId" -> enumForm[IncomeStatementService.Column],
        "direction" -> enumForm[SortDirection]
      )),
    ),
    "offset" -> opt(number(min = 0)),
  )

  case class LoadBalanceSheetData(
    params: BalanceSheetService.Params,
    offset: Option[Int],
  )

  val LOAD_BALANCE_SHEET_FORM: Form[LoadBalanceSheetData] = Form(
    "validation.get",
    LoadBalanceSheetData.apply,
    Tuples.to[LoadBalanceSheetData],
    "params" -> obj(
      BalanceSheetService.Params.apply,
      Tuples.to[BalanceSheetService.Params],
      "periodStart" -> opt(Instant.form),
      "periodEnd" -> opt(Instant.form),
      "groupBy" -> opt(enumForm[BalanceSheetService.GroupBy]),
      "groupBy2" -> opt(enumForm[BalanceSheetService.GroupBy2]),
      "currency" -> text(allowEmpty = false),
      "showOnly" -> opt(enumForm[BalanceSheetService.Column]),
      "productId" -> opt(text(allowEmpty = false)),
      "customerId" -> opt(text(allowEmpty = false)),
      "transactionId" -> opt(text(allowEmpty = false)),
      "accounts" -> seq(text),
      "columns" -> seq(enumForm[BalanceSheetService.Column]),
      "sorts" -> seq(obj(
        BalanceSheetService.Sort.apply,
        Tuples.to[BalanceSheetService.Sort],
        "columnId" -> enumForm[BalanceSheetService.Column],
        "direction" -> enumForm[SortDirection]
      )),
    ),
    "offset" -> opt(number(min = 0)),
  )

  case class LoadArAgingData(
    params: ArAgingService.Params,
    offset: Option[Int],
  )

  val LOAD_AR_AGING_FORM: Form[LoadArAgingData] = Form(
    "validation.get",
    LoadArAgingData.apply,
    Tuples.to[LoadArAgingData],
    "params" -> obj(
      ArAgingService.Params.apply,
      Tuples.to[ArAgingService.Params],
      "exclusiveUpUntil" -> Instant.form,
      "groupBy" -> enumForm[ArAgingService.GroupBy],
      "currency" -> text(allowEmpty = false),
      "customerId" -> opt(text(allowEmpty = false)),
      "columns" -> seq(enumForm[ArAgingService.Column]),
      "sorts" -> seq(obj(
        ArAgingService.Sort.apply,
        Tuples.to[ArAgingService.Sort],
        "columnId" -> enumForm[ArAgingService.Column],
        "direction" -> enumForm[SortDirection]
      )),
    ),
    "offset" -> opt(number(min = 0)),
  )

  case class LoadRevenueWaterfallData(
    params: RevenueWaterfallService.Params,
    offset: Option[Int],
  )

  val LOAD_REVENUE_WATERFALL_FORM: Form[LoadRevenueWaterfallData] = Form(
    "validation.get",
    LoadRevenueWaterfallData.apply,
    Tuples.to[LoadRevenueWaterfallData],
    "params" -> obj(
      RevenueWaterfallService.Params.apply,
      Tuples.to[RevenueWaterfallService.Params],
      "periodStart" -> Instant.form,
      "periodEnd" -> Instant.form,
      "currency" -> text(allowEmpty = false),
      "groupBy" -> enumForm[RevenueWaterfallService.GroupBy],
      "productId" -> opt(text(allowEmpty = false)),
      "customerId" -> opt(text(allowEmpty = false)),
      "transactionId" -> opt(text(allowEmpty = false)),
      "columns" -> seq(enumForm[RevenueWaterfallService.Column]),
      "sorts" -> seq(obj(
        RevenueWaterfallService.Sort.apply,
        Tuples.to[RevenueWaterfallService.Sort],
        "columnId" -> PeriodColumn.form[RevenueWaterfallService.Column],
        "direction" -> enumForm[SortDirection]
      )),
    ),
    "offset" -> opt(number(min = 0)),
  )

  case class LoadTransactionListData(
    params: TransactionListService.Params,
    offset: Option[Int],
  )

  val LOAD_TRANSACTION_LIST_FORM: Form[LoadTransactionListData] = Form(
    "validation.get",
    LoadTransactionListData.apply,
    Tuples.to[LoadTransactionListData],
    "params" -> obj(
      TransactionListService.Params.apply,
      Tuples.to[TransactionListService.Params],
      "customerId" -> opt(text).pipe[Option[Option[String]]](
        bind = {
          case Some("empty") => Some(None)
          case Some(id) => Some(Some(id))
          case None => None
        },
        unbind = {
          case None => None
          case Some(None) => Some("empty")
          case Some(Some(id)) => Some(id)
        }
      ),
      "keyword" -> text,
      "columns" -> seq(enumForm[TransactionListService.Column]),
      "sorts" -> seq(obj(
        TransactionListService.Sort.apply,
        Tuples.to[TransactionListService.Sort],
        "columnId" -> enumForm[TransactionListService.Column],
        "direction" -> enumForm[SortDirection]
      )),
    ),
    "offset" -> opt(number(min = 0)),
  )

  case class LoadTransactionDetailData(
    transactionId: String
  )

  val LOAD_TRANSACTION_DETAIL_FORM: Form[LoadTransactionDetailData] = Form(
    "validation.get",
    LoadTransactionDetailData.apply,
    Tuples.to[LoadTransactionDetailData],
    "transactionId" -> text(allowEmpty = false),
  )

  case class ReprocessTransactionData(
    transactionId: String,
  )
  val REPROCESS_TRANSACTION_FORM: Form[ReprocessTransactionData] = Form(
    "validation.get",
    ReprocessTransactionData.apply,
    Tuples.to[ReprocessTransactionData],
    "transactionId" -> text(allowEmpty = false),
  )
}

@Singleton
class DashboardController @Inject() (
  debitsAndCreditsService: DebitsAndCreditsService,
  exportedFileService: ExportedFileService,
  arAgingService: ArAgingService,
  revenueWaterfallService: RevenueWaterfallService,
  customerService: CustomerService,
  revRecTransactionService: RevRecTransactionService,
  incomeStatementService: IncomeStatementService,
  balanceSheetService: BalanceSheetService,
  netRevenueService: NetRevenueService,
  activeCustomerChartService: ActiveCustomerChartService,
  monthlyArpaChartService: MonthlyArpaChartService,
  monthlyNrrService: MonthlyNrrService,
  monthlyGrrService: MonthlyGrrService,
  arAgingChartService: ArAgingChartService,
  contractualLiabilityService: ContractualLiabilityService,
  accountChangeByEventService: AccountChangeByEventService,
  invoiceLineItemService: InvoiceLineItemService,
  processTransactionWorker: ProcessTransactionWorker,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BaseController(cc) {
  import DashboardController.*

  def index(): play.api.mvc.Action[AnyContent] = authenticated() { implicit req =>
    Future(Ok(views.html.dashboard.index()))
  }

  def viewTransaction(transactionId: String): play.api.mvc.Action[AnyContent] = authenticated() { implicit req =>
    for {
      transaction <- revRecTransactionService.getRichById(req.stripeAccountId, req.liveMode, transactionId).map(_.get)
    } yield {
      Ok(views.html.dashboard.viewTransaction(transaction))
    }
  }

  def viewLineItem(lineItemId: String): play.api.mvc.Action[AnyContent] = authenticated() { implicit req =>
    for {
      lineItem <- invoiceLineItemService.getById(lineItemId).map(_.get)
    } yield {
      Redirect(Seq(
        routes.DashboardController.viewTransaction(lineItem.invoiceId),
        s"?il=${lineItem.id}"
      ).mkString)
    }
  }

  def loadTransactionDetail(): play.api.mvc.Action[AnyContent] = authenticated() { implicit req =>
    val data = LOAD_TRANSACTION_DETAIL_FORM.bindFromRequest().get

    for {
      transaction <- revRecTransactionService.getRichById(req.stripeAccountId, req.liveMode, data.transactionId).map(_.get)
    } yield {
      Ok(Json.obj(
        "detail" -> TransactionDetail(transaction).toJson(),
      ))
    }
  }


  def revenueWaterfall(): play.api.mvc.Action[AnyContent] = authenticated() { implicit req =>
    Future(Ok(views.html.dashboard.revenueWaterfall()))
  }

  def loadRevenueWaterfall(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_REVENUE_WATERFALL_FORM.bindFromRequest().get

    for {
      totalNumberOfRows <- revenueWaterfallService.count(req.stripeAccountId, req.liveMode, data.params)
      result <- revenueWaterfallService.get(req.stripeAccountId, req.liveMode, data.params, data.offset.getOrElse(0), 200)
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

  def exportRevenueWaterfall(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_REVENUE_WATERFALL_FORM.bindFromRequest().get

    val tmpFile = java.io.File.createTempFile(s"revenue-waterfall-", ".csv")
    for {
      _ <- revenueWaterfallService.exportToCsv(req.stripeAccountId, req.liveMode, data.params, tmpFile)
      exportedFile <- exportedFileService.create(s"${req.stripeAccountId}-${if (req.liveMode) { "live" } else { "test" }}-revenue-waterfall.csv", tmpFile.getAbsolutePath)
    } yield {
      Ok(Json.obj("url" -> routes.ExportedFileController.download(exportedFile.id).url))
    }
  }

  def arAging(): play.api.mvc.Action[AnyContent] = authenticated() { implicit req =>
    Future(Ok(views.html.dashboard.arAging()))
  }

  def loadArAging(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_AR_AGING_FORM.bindFromRequest().get

    for {
      totalNumberOfRows <- arAgingService.count(req.stripeAccountId, req.liveMode, data.params)
      result <- arAgingService.get(req.stripeAccountId, req.liveMode, data.params, data.offset.getOrElse(0), 200)
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

  def exportArAging(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_AR_AGING_FORM.bindFromRequest().get

    val tmpFile = java.io.File.createTempFile("ar-aging", ".csv")
    for {
      _ <- arAgingService.exportToCsv(req.stripeAccountId, req.liveMode, data.params, tmpFile)
      exportedFile <- exportedFileService.create(s"${req.stripeAccountId}-${if (req.liveMode) { "live" } else { "test" }}-ar-aging.csv", tmpFile.getAbsolutePath)
    } yield {
      Ok(Json.obj("url" -> routes.ExportedFileController.download(exportedFile.id).url))
    }
  }

  def incomeStatement(): play.api.mvc.Action[AnyContent] = authenticated() { implicit req =>
    Future(Ok(views.html.dashboard.incomeStatement()))
  }

  def loadIncomeStatement(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_INCOME_STATEMENT_FORM.bindFromRequest().get

    for {
      totalNumberOfRows <- incomeStatementService.count(req.stripeAccountId, req.liveMode, data.params)
      result <- incomeStatementService.get(req.stripeAccountId, req.liveMode, data.params, data.offset.getOrElse(0), 200)
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

  def exportIncomeStatement(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_INCOME_STATEMENT_FORM.bindFromRequest().get

    val tmpFile = java.io.File.createTempFile("income-statement", ".csv")
    for {
      _ <- incomeStatementService.exportToCsv(req.stripeAccountId, req.liveMode, data.params, tmpFile)
      exportedFile <- exportedFileService.create(s"${req.stripeAccountId}-${if (req.liveMode) { "live" } else { "test" }}-income-statement.csv", tmpFile.getAbsolutePath)
    } yield {
      Ok(Json.obj("url" -> routes.ExportedFileController.download(exportedFile.id).url))
    }
  }

  def balanceSheet(): play.api.mvc.Action[AnyContent] = authenticated() { implicit req =>
    Future(Ok(views.html.dashboard.balanceSheet()))
  }

  def loadBalanceSheet(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_BALANCE_SHEET_FORM.bindFromRequest().get

    for {
      totalNumberOfRows <- balanceSheetService.count(req.stripeAccountId, req.liveMode, data.params)
      result <- balanceSheetService.get(req.stripeAccountId, req.liveMode, data.params, data.offset.getOrElse(0), 200)
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

  def exportBalanceSheet(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_BALANCE_SHEET_FORM.bindFromRequest().get

    val tmpFile = java.io.File.createTempFile("balance-sheet", ".csv")
    for {
      _ <- balanceSheetService.exportToCsv(req.stripeAccountId, req.liveMode, data.params, tmpFile)
      exportedFile <- exportedFileService.create(s"${req.stripeAccountId}-${if (req.liveMode) { "live" } else { "test" }}-balance-sheet.csv", tmpFile.getAbsolutePath)
    } yield {
      Ok(Json.obj("url" -> routes.ExportedFileController.download(exportedFile.id).url))
    }
  }

  def debitsAndCredits(): play.api.mvc.Action[AnyContent] = authenticated() { implicit req =>
    Future(Ok(views.html.dashboard.debitsAndCredits()))
  }

  def loadDebitsAndCredits(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_DEBITS_AND_CREDITS_FORM.bindFromRequest().get

    for {
      totalNumberOfRows <- debitsAndCreditsService.count(req.stripeAccountId, req.liveMode, data.params)
      result <- debitsAndCreditsService.get(req.stripeAccountId, req.liveMode, data.params, data.offset.getOrElse(0), 200)
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

  def exportDebitsAndCredits(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_DEBITS_AND_CREDITS_FORM.bindFromRequest().get

    val tmpFile = java.io.File.createTempFile("debits-and-credits-", ".csv")
    for {
      _ <- debitsAndCreditsService.exportToCsv(req.stripeAccountId, req.liveMode, data.params, tmpFile)
      exportedFile <- exportedFileService.create(s"${req.stripeAccountId}-${if (req.liveMode) { "live" } else { "test" }}-debits-and-credits.csv", tmpFile.getAbsolutePath)
    } yield {
      Ok(Json.obj("url" -> routes.ExportedFileController.download(exportedFile.id).url))
    }
  }

  def loadAccountSummary(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_ACCOUNT_SUMMARY_FORM.bindFromRequest().get

    for {
      entries <- debitsAndCreditsService.getAccountSummary(req.stripeAccountId, req.liveMode, data.transactionId, data.lineItemId)
    } yield {
      Ok(Json.obj(
        "entries" -> entries.map(_.toJson())
      ))
    }
  }

  def loadLineItems(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_LINE_ITEMS_FORM.bindFromRequest().get

    for {
      transaction <- revRecTransactionService.getById(req.stripeAccountId, req.liveMode, data.transactionId.get).map(_.get)
      lineItems <- if (transaction.tpe == RevRecTransaction.Type.Invoice) {
        invoiceLineItemService.getByInvoice(transaction.id)
      } else {
        Future(Seq.empty)
      }
    } yield {
      Ok(Json.obj(
        "lineItems" -> lineItems.map(_.toJson())
      ))
    }
  }

  def loadOverview(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = LOAD_OVERVIEW_FORM.bindFromRequest().get

    val netRevenue = netRevenueService.getDataPoints(req.stripeAccountId, req.liveMode, data.currency, data.periodStart, data.periodEnd)
    val activeCustomers = activeCustomerChartService.get(req.stripeAccountId, req.liveMode, data.currency, data.periodStart, data.periodEnd)
    val monthlyArpa = monthlyArpaChartService.get(req.stripeAccountId, req.liveMode, data.currency, data.periodStart, data.periodEnd)
    val monthlyNrr = monthlyNrrService.get(req.stripeAccountId, req.liveMode, data.currency, data.periodStart, data.periodEnd)
    val monthlyGrr = monthlyGrrService.get(req.stripeAccountId, req.liveMode, data.currency, data.periodStart, data.periodEnd)
    val deferredRevenue = contractualLiabilityService.get(
      req.stripeAccountId, req.liveMode, Seq(JournalEntry.Account.DeferredRevenue), data.periodStart, data.periodEnd, data.currency)
    val deferredRevenueChanges = contractualLiabilityService.getChange(
      req.stripeAccountId, req.liveMode, Seq(JournalEntry.Account.DeferredRevenue), data.periodStart, data.periodEnd, data.currency)

    val otherContractualLiabilityAccounts = JournalEntry.Account.values.filter { a =>
      a != JournalEntry.Account.DeferredRevenue && a.getAccountCategory() == JournalEntry.AccountCategory.ContractLiability
    }.toList
    val otherContractualLiabilities = contractualLiabilityService.get(
      req.stripeAccountId, req.liveMode, otherContractualLiabilityAccounts, data.periodStart, data.periodEnd, data.currency)
    val otherContractualLiabilityChanges = contractualLiabilityService.getChange(
      req.stripeAccountId, req.liveMode, otherContractualLiabilityAccounts, data.periodStart, data.periodEnd, data.currency)

    val arAging = arAgingChartService.get(req.stripeAccountId, req.liveMode, data.currency, toMonthEnd(data.periodEnd))
    val directCashFlow = accountChangeByEventService.getChangesByMonth(req.stripeAccountId, req.liveMode, data.currency, data.periodStart, data.periodEnd, JournalEntry.Account.Cash)

    for {
      netRevenue <- netRevenue
      activeCustomer <- activeCustomers
      monthlyArpa <- monthlyArpa
      monthlyNrr <- monthlyNrr
      monthlyGrr <- monthlyGrr
      deferredRevenue <- deferredRevenue
      deferredRevenueChanges <- deferredRevenueChanges
      otherContractualLiabilities <- otherContractualLiabilities
      otherContractualLiabilityChanges <- otherContractualLiabilityChanges
      arAging <- arAging
      cashFlow <- directCashFlow
    } yield {
      Ok(Json.obj(
        "netRevenue" -> netRevenue.map(_.toJson()),
        "activeCustomers" -> activeCustomer.map(_.toJson()),
        "monthlyArpa" -> monthlyArpa.map(_.toJson()),
        "monthlyNrr" -> monthlyNrr.map(_.toJson()),
        "monthlyGrr" -> monthlyGrr.map(_.toJson()),
        "deferredRevenue" -> deferredRevenue.map(_.toJson()),
        "deferredRevenueChanges" -> deferredRevenueChanges.map(_.toJson()),
        "otherContractualLiabilities" -> otherContractualLiabilities.map(_.toJson()),
        "otherContractualLiabilityChanges" -> otherContractualLiabilityChanges.map(_.toJson()),
        "arAging" -> arAging.map(_.toJson()),
        "directCashFlow" -> cashFlow.map(_.toJson())
      ))
    }
  }

  def loadAccounts(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    for {
      accounts <- debitsAndCreditsService.getAllAccounts(req.stripeAccountId, req.liveMode)
    } yield {
      Ok(Json.obj(
        "accounts" -> accounts,
      ))
    }
  }

  def loadIncomeStatementAccounts(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val incomeStatementCategories = Seq(
      JournalEntry.AccountCategory.Revenue,
      JournalEntry.AccountCategory.ContraRevenue,
      JournalEntry.AccountCategory.Gain,
      JournalEntry.AccountCategory.Expense,
    )
    for {
      accounts <- debitsAndCreditsService.getAllAccounts(req.stripeAccountId, req.liveMode)
    } yield {
      Ok(Json.obj(
        "accounts" -> accounts.filter { a => incomeStatementCategories.contains(JournalEntry.Account.valueOf(a).getAccountCategory()) },
      ))
    }
  }

  def loadBalanceSheetAccounts(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val balanceSheetCategories = Seq(
      JournalEntry.AccountCategory.Asset,
      JournalEntry.AccountCategory.ContraAsset,
      JournalEntry.AccountCategory.ContractLiability,
      JournalEntry.AccountCategory.StatutoryLiability,
    )
    for {
      accounts <- debitsAndCreditsService.getAllAccounts(req.stripeAccountId, req.liveMode)
    } yield {
      Ok(Json.obj(
        "accounts" -> accounts.filter { a => balanceSheetCategories.contains(JournalEntry.Account.valueOf(a).getAccountCategory()) },
      ))
    }
  }

  def reprocessTransaction(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = REPROCESS_TRANSACTION_FORM.bindFromRequest().get
    for {
      transaction <- revRecTransactionService.getById(req.stripeAccountId, req.liveMode, data.transactionId).map(_.get)
    } yield {
      processTransactionWorker.makeProcessTransaction(transaction).foreach { processTransaction =>
        processTransactionWorker.generateJournalEntries(processTransaction, true)
      }
      Ok(Json.obj())
    }
  }
}

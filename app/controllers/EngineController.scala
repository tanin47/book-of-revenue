package controllers

import background.{ProcessTransactionWorker, StripeEventImporter, StripeImporter, StripeMeterEventSummaryImporter, StripeNormalizer}
import database.services.{RevRecTransactionService, JournalEntryService, RawStripeObjectService, TrackedExceptionService}
import database.services.JournalEntryService.ColumnType
import framework.{BaseController, ControllerComponents, Helpers, Jsonable, PlayConfig, Tuples}
import givers.form.Form
import givers.form.Mappings.{number, opt}
import org.jobrunr.jobs.states.StateName
import org.jobrunr.scheduling.JobRequestScheduler
import org.jobrunr.storage.{Paging, StorageProvider}
import play.api.libs.json.{JsNull, JsNumber, JsObject, JsString, JsValue, Json}
import play.api.mvc.{Action, AnyContent}

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.ListHasAsScala


@Singleton
class EngineController @Inject() (
  rawStripeObjectService: RawStripeObjectService,
  revRecTransactionService: RevRecTransactionService,
  trackedExceptionService: TrackedExceptionService,
  storageProvider: StorageProvider,
  config: PlayConfig,
  cc: ControllerComponents,
  jobScheduler: JobRequestScheduler
)(implicit ec: ExecutionContext) extends BaseController(cc) {

  def index(): Action[AnyContent] = authenticated() { implicit req =>
    Future(Ok(views.html.dashboard.engine()))
  }

  def run(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    Helpers.queueBootstrapJobs(jobScheduler)
    Future(Ok(Json.obj()))
  }

  def load(): Action[JsValue] = authenticated(parse.json) { implicit req =>
    for {
      importerStats <- rawStripeObjectService.getStats(req.stripeAccountId, req.liveMode, onlyProcessed = false)
      transformerStats <- rawStripeObjectService.getStats(req.stripeAccountId, req.liveMode, onlyProcessed = true)
      transactionStats <- revRecTransactionService.getStats(req.stripeAccountId, req.liveMode)
      recentTransactions <- revRecTransactionService.getAllListable(req.stripeAccountId, req.liveMode, 0, 5)
      recentExceptions <- trackedExceptionService.getAll(5)
    } yield {
      val jobClassNames = storageProvider
        .getJobList(StateName.PROCESSING, Paging.AmountBasedList.ascOnCreatedAt(100))
        .asScala
        .map(_.getJobDetails.getClassName)
        .toSet
      Ok(Json.obj(
        "stages" -> Json.obj(
          "importer" -> Json.obj(
            "count" -> importerStats.count,
            "latestUpdatedAt" -> importerStats.maxUpdatedAt.map(_.toEpochMilli),
            "isRunning" -> (
              jobClassNames.contains(classOf[StripeImporter].getCanonicalName) ||
                jobClassNames.contains(classOf[StripeEventImporter].getCanonicalName)
            ),
          ),
          "transformer" -> Json.obj(
            "count" -> transformerStats.count,
            "latestUpdatedAt" -> transformerStats.maxUpdatedAt.map(_.toEpochMilli),
            "isRunning" -> (
              jobClassNames.contains(classOf[StripeNormalizer].getCanonicalName) ||
                jobClassNames.contains(classOf[StripeMeterEventSummaryImporter].getCanonicalName)
            )
          ),
          "processor" -> Json.obj(
            "count" -> transactionStats.count,
            "latestUpdatedAt" -> transactionStats.maxUpdatedAt.map(_.toEpochMilli),
            "isRunning" -> jobClassNames.contains(classOf[ProcessTransactionWorker].getCanonicalName),
          )
        ),
        "recentTransactions" -> recentTransactions.map(_.toJson()),
        "recentExceptions" -> recentExceptions.map(_.toJson())
      ))
    }
  }
}

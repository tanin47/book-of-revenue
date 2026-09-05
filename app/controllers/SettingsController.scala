package controllers

import database.services.{JournalEntryService, StripeAccountService, UserService}
import framework.Helpers.{makeValidationException, queueBootstrapJobs}
import framework.{BaseController, ControllerComponents, ExternalServiceException, PlayConfig, Tuples}
import givers.form.{Form, ValidationException}
import givers.form.Mappings.{boolean, text}
import org.jobrunr.scheduling.JobRequestScheduler
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.AnyContent
import services.StripeService

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

object SettingsController {
  case class AddNewApiKeyData(
    apiKey: String
  )

  val ADD_NEW_API_KEY_FORM: Form[AddNewApiKeyData] = Form(
    "validation.get",
    AddNewApiKeyData.apply,
    Tuples.to[AddNewApiKeyData],
    "apiKey" -> text(allowEmpty = false),
  )

  case class RemoveApiKeyData(
    stripeAccountId: String,
    liveMode: Boolean
  )

  val REMOVE_API_KEY_FORM: Form[RemoveApiKeyData] = Form(
    "validation.get",
    RemoveApiKeyData.apply,
    Tuples.to[RemoveApiKeyData],
    "stripeAccountId" -> text(allowEmpty = false),
    "liveMode" -> boolean,
  )
}

@Singleton
class SettingsController @Inject() (
  userService: UserService,
  stripeService: StripeService,
  stripeAccountService: StripeAccountService,
  journalEntryService: JournalEntryService,
  jobScheduler: JobRequestScheduler,
  config: PlayConfig,
  cc: ControllerComponents
)(implicit ec: ExecutionContext) extends BaseController(cc) {
  import SettingsController.*

  def index(): play.api.mvc.Action[AnyContent] = authenticated() { implicit req =>
    Future(Ok(views.html.dashboard.settings()))
  }

  def loadStripeAccounts(): play.api.mvc.Action[JsValue] = authenticatedNoStripeAccount(parse.json) { implicit req =>
    for {
      stripeAccounts <- stripeAccountService.getAll()
    } yield {
      Ok(Json.obj(
        "stripeAccounts" -> stripeAccounts.map(_.toJson())
      ))
    }
  }

  def addNewApiKey(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = ADD_NEW_API_KEY_FORM.bindFromRequest().get

    for {
      stripeAccount <- stripeAccountService.addApiKey(data.apiKey)
    } yield {
      queueBootstrapJobs(jobScheduler)
      Ok(Json.obj(
        "stripeAccount" -> stripeAccount.toJson(),
      ))
    }
  }

  def removeApiKey(): play.api.mvc.Action[JsValue] = authenticated(parse.json) { implicit req =>
    val data = REMOVE_API_KEY_FORM.bindFromRequest().get

    for {
      _ <- stripeAccountService.removeApiKey(data.stripeAccountId, data.liveMode)
    } yield {
      Ok(Json.obj())
    }
  }

  def loadCurrencies(): play.api.mvc.Action[AnyContent] = authenticated() { implicit req =>
    journalEntryService.getAllCurrencies(req.stripeAccountId, req.liveMode).map { currencies =>
      Ok(Json.obj("currencies" -> (currencies ++ Seq(req.currentStripeAccount.stripeAccount.defaultCurrency)).sorted))
    }
  }

  def switchCurrency(currency: String, redirectUrl: Option[String]): play.api.mvc.Action[AnyContent] = authenticated() { implicit req =>
    Future(setCurrency(
      Redirect(redirectUrl.getOrElse("/")),
      currency
    ))
  }
}

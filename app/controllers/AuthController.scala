package controllers

import database.services.UserService.UsernameAlreadyExistingException
import database.services.{JournalEntryService, StripeAccountService, UserService}
import framework.*
import framework.Helpers.{makeValidationException, queueBootstrapJobs}
import givers.form.Form
import givers.form.Mappings.{email, text}
import org.jobrunr.scheduling.{JobRequestScheduler, JobScheduler}
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, AnyContent}
import services.StripeService

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object AuthController {
  case class LoginData(
    username: String,
    password: String,
  )

  val LOGIN_FORM: Form[LoginData] = Form(
    "validation.login",
    LoginData.apply,
    Tuples.to[LoginData],
    "username" -> text(allowEmpty = false),
    "password" -> text(allowEmpty = false),
  )

  case class RegisterData(
    username: String,
    password: String,
    stripeApiKey: String
  )

  val REGISTER_FORM: Form[RegisterData] = Form(
    "validation.register",
    RegisterData.apply,
    Tuples.to[RegisterData],
    "username" -> text(allowEmpty = false),
    "password" -> text(allowEmpty = false),
    "stripeApiKey" -> text(allowEmpty = false),
  )
}

@Singleton
class AuthController @Inject() (
  userService: UserService,
  stripeAccountService: StripeAccountService,
  journalEntryService: JournalEntryService,
  stripeService: StripeService,
  jobScheduler: JobRequestScheduler,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BaseController(cc) {
  import AuthController.*

  def login(redirectPath: Option[String]): Action[AnyContent] = async() { implicit req =>
    for {
      users <- userService.getAll()
      accounts <- stripeAccountService.getAll()
    } yield {
      if (users.isEmpty && accounts.isEmpty) {
        Redirect(routes.AuthController.register().url)
      } else {
        Ok(views.html.auth.login(redirectPath.getOrElse("/")))
      }
    }
  }

  def doLogin(): Action[JsValue] = async(parse.json) { implicit req =>
    val data = LOGIN_FORM.bindFromRequest().get

    for {
      user <- userService
        .getByUsername(data.username)
        .map(_.getOrElse { throw makeValidationException("validation.login.username.error.notExist") })
      stripeAccounts <- stripeAccountService.getAll()
    } yield {
      if (!new BCryptPasswordEncoder().matches(data.password, user.hashedPassword)) {
        throw makeValidationException("validation.login.password.error.invalid")
      }

      val result = setLoggedInUser(Ok(Json.obj()), user)

      if (stripeAccounts.size == 1 && (stripeAccounts.head.liveModeApiKey.nonEmpty || stripeAccounts.head.testModeApiKey.nonEmpty)) {
        setStripeAccountAndMode(
          result = result,
          stripeAccountId = stripeAccounts.head.id,
          liveMode = if (stripeAccounts.head.liveModeApiKey.nonEmpty) {
            true
          } else if (stripeAccounts.head.testModeApiKey.nonEmpty) {
            false
          } else {
            throw new RuntimeException("No stripe account api key")
          },
          overwrite = false
        )
      } else {
        result
      }
    }
  }

  def register(): Action[AnyContent] = async() { implicit req =>
    Future(Ok(views.html.auth.register()))
  }

  def doRegister(): Action[JsValue] = async(parse.json) { implicit req =>
    val data = REGISTER_FORM.bindFromRequest().get

    for {
      users <- userService.getAll()
      _ <- stripeAccountService.getAll().map { stripeAccounts =>
        if (stripeAccounts.nonEmpty || users.nonEmpty) {
          throw makeValidationException("validation.register.error.alreadySetup")
        }
        ()
      }
      stripeAccount <- stripeAccountService.addApiKey(data.stripeApiKey)
      user <- userService
        .create(
          UserService.CreateData(
            username = data.username,
            password = data.password
          )
        )
        .recover { case UsernameAlreadyExistingException =>
          throw makeValidationException("validation.register.username.error.alreadyExists")
        }
    } yield {
      setStripeAccountAndMode(
        result = setLoggedInUser(Ok(Json.obj()), user),
        stripeAccountId = stripeAccount.id,
        liveMode = if (stripeAccount.liveModeApiKey.nonEmpty) {
          true
        } else if (stripeAccount.testModeApiKey.nonEmpty) {
          false
        } else {
          throw new RuntimeException("No stripe account api key")
        },
      )
    }
  }

  def logout(): Action[AnyContent] = async() { implicit req =>
    Future(clearLoggedInUser(Redirect("/")))
  }

  def selectStripeAccount(): Action[AnyContent] = authenticatedNoStripeAccount() { implicit req =>
    Future(Ok(views.html.selectStripeAccount()))
  }

  def switchStripeAccount(accountId: String, mode: String): Action[AnyContent] = authenticatedNoStripeAccount() { implicit req =>
    for {
      account <- stripeAccountService.getById(accountId).map(_.get)
      currencies <- journalEntryService.getAllCurrencies(account.id, mode == "live")
    } yield {
      if (mode == "live") {
        if (account.liveModeApiKey.isEmpty) { throw NotFoundException() }
      } else if (mode == "test") {
        if (account.testModeApiKey.isEmpty) { throw NotFoundException() }
      } else {
        throw NotFoundException()
      }

      setCurrency(
        result = setStripeAccountAndMode(
          result = Redirect("/"),
          stripeAccountId = account.id,
          liveMode = mode == "live"
        ),
        currency = req.currencyOpt.filter(currencies.contains).getOrElse(account.defaultCurrency)
      )
    }
  }
}

package framework

import database.models.{StripeAccount, User}
import database.services.{JournalEntryService, StripeAccountService, UserService}
import play.api.http.FileMimeTypes
import play.api.i18n.{Langs, MessagesApi}
import play.api.libs.json.{JsObject, Json}
import play.api.libs.typedmap.TypedMap
import play.api.mvc.*
import play.api.mvc.request.{RemoteConnection, RequestTarget}
import process.Helpers.getAccountingPeriod

import java.time.temporal.ChronoUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

case class CurrentStripeAccount(
  stripeAccount: StripeAccount,
  liveMode: Boolean
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "stripeAccount" -> stripeAccount.toJson(),
    "liveMode" -> liveMode
  )
}

class Request[A](
  val loggedInUserOpt: Option[User],
  val currentStripeAccountOpt: Option[CurrentStripeAccount],
  val currencyOpt: Option[String],
  val firstAccountingPeriodOpt: Option[Instant],
  val req: play.api.mvc.Request[A],
  val config: PlayConfig
) extends play.api.mvc.Request[A] {

  override def body: A = req.body

  override def connection: RemoteConnection = req.connection

  override def method: String = req.method

  override def target: RequestTarget = req.target

  override def version: String = req.version

  override def headers: Headers = req.headers

  override def attrs: TypedMap = req.attrs

  lazy val firstAccountingPeriod: Instant = firstAccountingPeriodOpt.getOrElse(getAccountingPeriod(Instant.now().minus(366, ChronoUnit.DAYS)))
}

class AuthRequest[A](override val req: Request[A], override val config: PlayConfig) extends Request[A](
  req.loggedInUserOpt,
  req.currentStripeAccountOpt,
  req.currencyOpt,
  req.firstAccountingPeriodOpt,
  req.req,
  config
) {
  lazy val loggedInUser: User = loggedInUserOpt.get
  lazy val currentStripeAccount: CurrentStripeAccount = currentStripeAccountOpt.get
  lazy val stripeAccountId: String = currentStripeAccount.stripeAccount.id
  lazy val liveMode: Boolean = currentStripeAccount.liveMode
}

@Singleton
case class ControllerComponents @Inject() (
  userService: UserService,
  stripeAccountService: StripeAccountService,
  journalEntryService: JournalEntryService,
  messagesActionBuilder: MessagesActionBuilder,
  actionBuilder: DefaultActionBuilder,
  parsers: PlayBodyParsers,
  messagesApi: MessagesApi,
  langs: Langs,
  fileMimeTypes: FileMimeTypes,
  config: PlayConfig,
  executionContext: scala.concurrent.ExecutionContext
) extends play.api.mvc.MessagesControllerComponents

object BaseController {
  val USER_ID_SESSION_KEY = "user"
  val STRIPE_ACCOUNT_ID_SESSION_KEY = "stripeAccountId"
  val STRIPE_MODE_SESSION_KEY = "stripeMode"
  val CURRENCY_SESSION_KEY = "currency"
}

abstract class BaseController(cc: ControllerComponents)(implicit ec: ExecutionContext)
    extends play.api.mvc.MessagesAbstractController(cc) {
  import BaseController.*

  def async[T](parser: BodyParser[T] = parse.anyContent)(fn: Request[T] => Future[Result]): Action[T] =
    Action.async(parser) { baseReq =>
      for {
        req <- convert(baseReq)
        result <- fn(req)
      } yield {
        result
      }
    }
  def authenticatedNoStripeAccount[T](parser: BodyParser[T] = parse.anyContent)(fn: AuthRequest[T] => Future[Result]): Action[T] = {
    async(parser) { req =>
      if (req.loggedInUserOpt.isEmpty) {
        throw new AuthenticationRequiredException
      }

      fn(new AuthRequest[T](req, req.config))
    }
  }

  def authenticated[T](parser: BodyParser[T] = parse.anyContent)(fn: AuthRequest[T] => Future[Result]): Action[T] = {
    authenticatedNoStripeAccount(parser) { req =>
      if (req.currentStripeAccountOpt.isEmpty) {
        throw new StripeAccountSelectionRequiredException
      }

      fn(new AuthRequest[T](req, req.config))
    }
  }

  def setCurrency(result: Result, currency: String)(implicit req: RequestHeader): Result = {
    result.addingToSession(
      CURRENCY_SESSION_KEY -> currency,
    )
  }

  def setStripeAccountAndMode(
    result: Result,
    stripeAccountId: String,
    liveMode: Boolean,
    overwrite: Boolean = true
  )(implicit req: RequestHeader): Result = {
    val liveModeValue = if (liveMode) { "live" } else { "test" }
    result.addingToSession(
      STRIPE_ACCOUNT_ID_SESSION_KEY -> (if (overwrite) {
        stripeAccountId
      } else {
        result.session.get(STRIPE_ACCOUNT_ID_SESSION_KEY).getOrElse(stripeAccountId)
      }),
      STRIPE_MODE_SESSION_KEY -> (if (overwrite) {
        liveModeValue
      } else {
        result.session.get(STRIPE_MODE_SESSION_KEY).getOrElse(liveModeValue)
      })
    )
  }

  def clearStripeAccountAndMode(result: Result)(implicit req: RequestHeader): Result = {
    result.removingFromSession(STRIPE_ACCOUNT_ID_SESSION_KEY, STRIPE_MODE_SESSION_KEY)
  }

  def setLoggedInUser(result: Result, loggedInUser: User)(implicit req: RequestHeader): Result = {
    result.addingToSession(USER_ID_SESSION_KEY -> loggedInUser.id)
  }

  def clearLoggedInUser(result: Result)(implicit req: RequestHeader): Result = {
    result.removingFromSession(USER_ID_SESSION_KEY)
  }

  private[this] def convert[T](baseReq: play.api.mvc.Request[T]): Future[Request[T]] = {
    val userIdOpt = try {
      baseReq.session.get(USER_ID_SESSION_KEY)
    } catch {
      case _: Exception => None
    }
    val stripeAccountIdOpt = try {
      baseReq.session.get(STRIPE_ACCOUNT_ID_SESSION_KEY)
    } catch {
      case _: Exception => None
    }
    val stripeModeOpt = try {
      baseReq.session.get(STRIPE_MODE_SESSION_KEY)
    } catch {
      case _: Exception => None
    }
    val currencyOpt = try {
      baseReq.session.get(CURRENCY_SESSION_KEY)
    } catch {
      case _: Exception => None
    }

    for {
      userOpt <- userIdOpt match {
        case Some(userId) => cc.userService.getById(userId)
        case None         => Future.successful(None)
      }
      currentStripeAccountOpt <- (stripeAccountIdOpt, stripeModeOpt) match {
        case (Some(stripeAccountId), Some(mode)) =>
          cc.stripeAccountService.getById(stripeAccountId).map {
            case Some(stripeAccount) =>
              if ((mode == "live" && stripeAccount.liveModeApiKey.isDefined) || (mode == "test" && stripeAccount.testModeApiKey.isDefined)) {
                Some(CurrentStripeAccount(stripeAccount, mode == "live"))
              } else {
                None
              }
            case None => None
          }
        case _ => Future(None)
      }
      firstAccountingPeriod <- currentStripeAccountOpt match {
        case Some(currentStripeAccount) =>
          cc.journalEntryService.getFirstAccountingPeriod(currentStripeAccount.stripeAccount.id, currentStripeAccount.liveMode)
        case None => Future(None)
      }
    } yield {
      framework.Request(userOpt, currentStripeAccountOpt, currencyOpt, firstAccountingPeriod, baseReq, cc.config)
    }
  }
}

package controllers

import background.{LetsencryptCertificateIssuer, LetsencryptCertificateIssuerRequest}
import database.services.{JournalEntryService, StripeAccountService, UserService}
import framework.{BaseController, ControllerComponents, NotFoundException, PlayConfig}
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, AnyContent}

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class HomeController @Inject() (
  userService: UserService,
  letsencryptCertificateIssuer: LetsencryptCertificateIssuer,
  config: PlayConfig,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BaseController(cc) {

  def index(): play.api.mvc.Action[AnyContent] = async() { implicit req =>
    Future(Redirect(routes.DashboardController.index()))
  }

  def privacy() = async() { implicit req =>
    Future(Ok(views.html.static.privacy()))
  }

  def tos() = async() { implicit req =>
    Future(Ok(views.html.static.tos()))
  }

  def onboard(): play.api.mvc.Action[AnyContent] = async() { implicit req =>
    Future(Ok(views.html.onboard(config.APP_DOMAIN)))
  }

  def triggerCertIssuance(): Action[JsValue] = async(parse.json) { implicit req =>
    val _ = Future(letsencryptCertificateIssuer.run(LetsencryptCertificateIssuerRequest()))
    Future(Ok(Json.obj()))
  }

  def checkCert(): Action[AnyContent] = async() { implicit req =>
    Future(Ok(Json.obj("hasValidCert" -> config.HAS_VALID_SSL_CERT.exists(_.valid))))
  }
}

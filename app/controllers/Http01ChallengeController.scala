package controllers

import database.services.Http01ChallengeEntryService
import framework.{BaseController, ControllerComponents}
import play.api.mvc.AnyContent

import javax.inject.*
import scala.concurrent.ExecutionContext

@Singleton
class Http01ChallengeController @Inject() (
  http01ChallengeEntryService: Http01ChallengeEntryService,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BaseController(cc) {

  def get(token: String): play.api.mvc.Action[AnyContent] = async() { implicit req =>
    http01ChallengeEntryService.getByToken(token).map { entry =>
      Ok(entry.get.content)
    }
  }
}

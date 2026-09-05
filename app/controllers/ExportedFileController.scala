package controllers

import database.services.ExportedFileService
import framework.{BaseController, ControllerComponents, PlayConfig}
import play.api.mvc.AnyContent

import java.io.File
import javax.inject.*
import scala.concurrent.ExecutionContext

@Singleton
class ExportedFileController @Inject() (
  exportedFileService: ExportedFileService,
  config: PlayConfig,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BaseController(cc) {

  def download(exportedFileId: String): play.api.mvc.Action[AnyContent] = authenticated() { implicit req =>
    exportedFileService.getById(exportedFileId).map { exportedFile =>
      Ok.sendFile(
        content = new File(exportedFile.get.tmpFilePath),
        inline = false,
        fileName = { _ => Some(exportedFile.get.filename) }
      )
    }
  }
}

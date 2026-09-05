package database.services

import database.models.{ExportedFile, ExportedFileTable}
import framework.{BaseDbService, Instant}
import play.api.db.slick.DatabaseConfigProvider
import slick.lifted.TableQuery

import java.io.File
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ExportedFileService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
    extends BaseDbService {

  import framework.PostgresProfile.api.*

  private val query: TableQuery[ExportedFileTable] = TableQuery[ExportedFileTable]

  def create(stripeAccountId: String, liveMode: Boolean, name: String, file: File): Future[ExportedFile] = {
    val fileName = s"$stripeAccountId-${if (liveMode) { "live" } else { "test" } }-$name.csv"

    create(fileName, file.getAbsolutePath)
  }

  def create(filename: String, tmpFilePath: String): Future[ExportedFile] = {
    val entity = ExportedFile(
      id = "",
      filename = filename,
      tmpFilePath = tmpFilePath,
      createdAt = Instant.now()
    )

    db
      .run { (query returning query.map(_.id)) += entity }
      .map { id => entity.copy(id = id) }
  }

  def getById(id: String): Future[Option[ExportedFile]] = {
    db.run {
      query
        .filter { f => f.id === id }
        .result
        .headOption
    }
  }
}

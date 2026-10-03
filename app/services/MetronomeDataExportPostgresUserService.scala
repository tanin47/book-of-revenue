package services

import framework.{Jsonable, PlayConfig}
import play.api.Application
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsObject, Json}
import slick.jdbc.JdbcProfile

import java.net.URI
import java.security.SecureRandom
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

object MetronomeDataExportPostgresUserService {
  val METRONOME_DATA_EXPORT_SCHEMA_NAME = "metronome"
  val METRONOME_DATA_EXPORT_USERNAME_BASE = "bor_metronome_data_export_user"

  def generateRandomPassword(): String = {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
    val random = new SecureRandom()
    (1 to 24).map { i =>
      if (i == 12) {
        ","
      } else {
        chars.charAt(random.nextInt(chars.length))
      }
    }.mkString
  }

  case class MetronomeDataExportDetail(
    host: String,
    port: Int,
    databaseName: String,
    schemaName: String,
    username: String,
  ) extends Jsonable {
    def toJson(): JsObject = Json.obj(
      "host" -> host,
      "port" -> port,
      "databaseName" -> databaseName,
      "schemaName" -> schemaName,
      "username" -> username
    )
  }
}

class MetronomeDataExportPostgresUserService @Inject()(
  val dbConfigProvider: DatabaseConfigProvider,
  app: Application,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import MetronomeDataExportPostgresUserService.*
  import framework.PostgresProfile.api.*

  def getMetronomeDataExportDetail(): Future[MetronomeDataExportDetail] = {
    for {
      username <- getMetronomeUser()
    } yield {
      val postgresUrl = new URI(config.getString("slick.dbs.default.db.properties.url"))
       MetronomeDataExportDetail(
        host = postgresUrl.getHost,
        port = postgresUrl.getPort,
        databaseName = postgresUrl.getPath.substring(1),
        schemaName = METRONOME_DATA_EXPORT_SCHEMA_NAME,
        username = username,
      )
    }
  }

  def getMetronomeUsername(): String = {
    s"${METRONOME_DATA_EXPORT_USERNAME_BASE}_${app.mode.toString.toLowerCase}"
  }

  def getMetronomeUser(): Future[String] = {
    val username = getMetronomeUsername()
    val action = for {
      existing <- sql"SELECT 1 FROM pg_roles WHERE rolname = $username".as[Int].headOption
      _ <- existing match {
        case Some(_) => DBIO.successful(())
        case None =>
          println(s"Creating user $username")
          DBIO.seq(
            sqlu"""CREATE USER #$username PASSWORD '#${generateRandomPassword()}';""",
            sqlu"""GRANT ALL ON schema #$METRONOME_DATA_EXPORT_SCHEMA_NAME TO #$username;""",
            sqlu"""GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA #$METRONOME_DATA_EXPORT_SCHEMA_NAME TO #$username;""",
          )
      }
    } yield username

    db.run(action.transactionally)
  }

  def resetPassword(): Future[String] = {
    val newPassword = generateRandomPassword()
    val username = getMetronomeUsername()
    db.run {
      sqlu"""ALTER USER #$username WITH PASSWORD '#$newPassword';"""
    }.map { _ =>
      newPassword
    }
  }
}

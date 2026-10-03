package services

import framework.PlayConfig
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import java.security.SecureRandom
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

object MetronomeDataExportPostgresUserService {
  val METRONOME_DATA_EXPORT_SCHEMA_NAME = "metronome"
  val METRONOME_DATA_EXPORT_USERNAME = "bor_metronome_data_export_user"

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
}

class MetronomeDataExportPostgresUserService @Inject()(
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {
  import MetronomeDataExportPostgresUserService.*
  import framework.PostgresProfile.api.*

  def getMetronomeUsername(): Future[String] = {
    val action = for {
      existing <- sql"SELECT 1 FROM pg_roles WHERE rolname = $METRONOME_DATA_EXPORT_USERNAME".as[Int].headOption
      _ <- existing match {
        case Some(_) => DBIO.successful(())
        case None => DBIO.seq(
          sqlu"""CREATE USER #$METRONOME_DATA_EXPORT_USERNAME PASSWORD '#${generateRandomPassword()}';""",
          sqlu"""GRANT ALL ON schema #$METRONOME_DATA_EXPORT_SCHEMA_NAME TO #$METRONOME_DATA_EXPORT_USERNAME;"""
        )
      }
    } yield METRONOME_DATA_EXPORT_USERNAME

    db.run(action.transactionally)
  }

  def resetPassword(): Future[String] = {
    val newPassword = generateRandomPassword()
    db.run {
      sqlu"""ALTER USER #$METRONOME_DATA_EXPORT_USERNAME WITH PASSWORD '#$newPassword';"""
    }.map { _ =>
      newPassword
    }
  }
}

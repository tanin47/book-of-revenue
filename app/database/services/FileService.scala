package database.services

import database.models.{File, FileTable}
import framework.BaseDbService
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider
import slick.lifted.TableQuery

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object FileService {
  val LetsencryptAccountPrivateKeyName = "letsencrypt-account-private.key"
  val LetsencryptAccountPublicKeyName = "letsencrypt-account-public.key"
  val LetsencryptDomainKeyName = "letsencrypt-domain.key"
  val LetsencryptCertificateChainName = "letsencrypt-certificate-chain.pem"
}

@Singleton
class FileService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
    extends BaseDbService {

  import framework.PostgresProfile.api.*

  private val query: TableQuery[FileTable] = TableQuery[FileTable]

  def create(name: String, content: String): Future[File] = {
    val entity = File(
      name = name,
      content = content
    )

    db
      .run { query += entity }
      .recoverWith {
        case e: PSQLException if matchUniqueConstraintException(e, "file__name") => update(entity)
      }
      .map { _ => entity }
  }

  def update(entity: File): Future[Unit] = {
    db
      .run {
        query.filter(_.name === entity.name).update(entity)
      }
      .map(_ => ())
  }

  def getByName(name: String): Future[Option[File]] = {
    db.run {
      query
        .filter { f => f.name === name }
        .result
        .headOption
    }
  }

  def getLetsencryptAccountPrivateKey(): Future[Option[File]] = {
    getByName(FileService.LetsencryptAccountPrivateKeyName)
  }

  def writeLetsencryptAccountPrivateKey(content: String): Future[File] = {
    create(FileService.LetsencryptAccountPrivateKeyName, content)
  }

  def getLetsencryptAccountPublicKey(): Future[Option[File]] = {
    getByName(FileService.LetsencryptAccountPublicKeyName)
  }

  def writeLetsencryptAccountPublicKey(content: String): Future[File] = {
    create(FileService.LetsencryptAccountPublicKeyName, content)
  }

  def getLetsencryptDomainKey(): Future[Option[File]] = {
    getByName(FileService.LetsencryptDomainKeyName)
  }

  def writeLetsencryptDomainKey(content: String): Future[File] = {
    create(FileService.LetsencryptDomainKeyName, content)
  }

  def getLetsencryptCertificateChain(): Future[Option[File]] = {
    getByName(FileService.LetsencryptCertificateChainName)
  }

  def writeLetsencryptCertificateChain(content: String): Future[File] = {
    create(FileService.LetsencryptCertificateChainName, content)
  }
}

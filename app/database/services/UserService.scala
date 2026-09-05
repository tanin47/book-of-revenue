package database.services

import database.models.{User, UserTable}
import framework.UpdateField.NoUpdate
import framework.{BaseDbService, Instant, UpdateField}
import org.postgresql.util.PSQLException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import play.api.db.slick.DatabaseConfigProvider
import slick.lifted.TableQuery

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object UserService {
  case class CreateData(
    username: String,
    password: String,
    createdAt: Instant = Instant.now()
  )

  case class UpdateData(
    hashedPassword: UpdateField[String] = NoUpdate
  )

  case object UsernameAlreadyExistingException extends Exception

  def hashPassword(password: String): String = {
    new BCryptPasswordEncoder().encode(password)
  }
}

@Singleton
class UserService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
    extends BaseDbService {
  import UserService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[UserTable] = TableQuery[UserTable]

  def create(data: CreateData): Future[User] = {
    val entity = User(
      id = "",
      username = data.username,
      hashedPassword = hashPassword(data.password),
      createdAt = data.createdAt
    )

    val future = for {
      id <- db.run {
        (query returning query.map(_.id)) += entity
      }
    } yield {
      entity.copy(id = id)
    }

    future.recoverWith {
      case e: PSQLException if matchUniqueConstraintException(e, "user__username") =>
        throw UsernameAlreadyExistingException
    }
  }

  def getAll(): Future[Seq[User]] = {
    db.run { query.result }
  }

  def update(id: String, data: UpdateData): Future[Unit] = {
    val base = query.filter(_.id === id)

    val updates = Seq(
      data.hashedPassword.toOption.map { v => base.map(_.hashedPassword).update(v) }
    ).flatten

    db.run(DBIO.sequence(updates).transactionally).map(_ => ())

  }

  def getByUsername(username: String): Future[Option[User]] = {
    db.run {
      query.filter(_.username === username).result.headOption
    }
  }

  def getById(id: String): Future[Option[User]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def updatePassword(id: String, password: String): Future[Unit] = {
    update(
      id = id,
      data = UpdateData(
        hashedPassword = UpdateField(hashPassword(password)),
      )
    )
  }
}

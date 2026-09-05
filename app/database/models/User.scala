package database.models

import database.models.User.PreferredLang
import framework.Jsonable
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

import java.time.Instant

object User {
  enum PreferredLang extends Enum[PreferredLang] {
    case English, Thai, Japanese, German
  }
}

case class User(
  id: String,
  username: String,
  hashedPassword: String,
  createdAt: Instant
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "username" -> username,
    "createdAt" -> createdAt.toEpochMilli
  )

}

class UserTable(tag: Tag) extends Table[User](tag, "user") {
  def id: Rep[String] = column[String]("id", O.PrimaryKey, O.AutoInc)
  def username: Rep[String] = column[String]("username")
  def hashedPassword: Rep[String] = column[String]("hashed_password")
  def createdAt: Rep[Instant] = column[Instant]("created_at")

  def * : ProvenShape[User] = (
    id,
    username,
    hashedPassword,
    createdAt
  ).<>((User.apply _).tupled, User.unapply)
}

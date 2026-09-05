package database.models

import framework.{Instant, Jsonable}
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class TrackedException(
  createdAt: Instant,
  exceptionClass: String,
  message: String,
  stackTrace: String
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "createdAt" -> createdAt.toEpochMilli,
    "exceptionClass" -> exceptionClass,
    "message" -> message,
    "stackTrace" -> stackTrace
  )
}

class TrackedExceptionTable(tag: Tag) extends Table[TrackedException](tag, "tracked_exception") {
  def createdAt: Rep[Instant] = column[Instant]("created_at")
  def exceptionClass: Rep[String] = column[String]("exception_class")
  def message: Rep[String] = column[String]("message")
  def stackTrace: Rep[String] = column[String]("stack_trace")

  def * : ProvenShape[TrackedException] = (
    createdAt,
    exceptionClass,
    message,
    stackTrace
  ).<>((TrackedException.apply _).tupled, TrackedException.unapply)
}

package database.models.metronome

import framework.Instant
import framework.PostgresProfile.api.*
import slick.lifted.{ProvenShape, Rep}

case class MetronomeCreditType(
  id: String,
  environmentType: Option[String],
  name: Option[String],
  isCurrency: Option[Boolean],
  updatedAt: Option[Instant]
)

class MetronomeCreditTypeTable(tag: Tag) extends Table[MetronomeCreditType](tag, Some("metronome"), "credit_type") {
  def id: Rep[String] = column[String]("id")
  def environmentType: Rep[Option[String]] = column[Option[String]]("environment_type")
  def name: Rep[Option[String]] = column[Option[String]]("name")
  def isCurrency: Rep[Option[Boolean]] = column[Option[Boolean]]("is_currency")
  def updatedAt: Rep[Option[Instant]] = column[Option[Instant]]("updated_at")

  def * : ProvenShape[MetronomeCreditType] = (
    id,
    environmentType,
    name,
    isCurrency,
    updatedAt,
  ).<>((MetronomeCreditType.apply _).tupled, MetronomeCreditType.unapply)
}

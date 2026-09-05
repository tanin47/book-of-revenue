package database.models

import framework.Instant
import framework.PostgresProfile.api.*
import slick.lifted.{ProvenShape, Rep}

case class ExportedFile(
  id: String,
  filename: String,
  tmpFilePath: String,
  createdAt: Instant
)

class ExportedFileTable(tag: Tag) extends Table[ExportedFile](tag, "exported_file") {
  def id: Rep[String] = column[String]("id", O.PrimaryKey, O.AutoInc)
  def filename: Rep[String] = column[String]("filename")
  def tmpFilePath: Rep[String] = column[String]("tmp_file_path")
  def createdAt: Rep[Instant] = column[Instant]("created_at")

  def * : ProvenShape[ExportedFile] = (
    id,
    filename,
    tmpFilePath,
    createdAt
  ).<>((ExportedFile.apply _).tupled, ExportedFile.unapply)
}

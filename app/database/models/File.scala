package database.models

import framework.PostgresProfile.api.*
import slick.lifted.{ProvenShape, Rep}

case class File(
  name: String,
  content: String
)

class FileTable(tag: Tag) extends Table[File](tag, "file") {
  def name: Rep[String] = column[String]("name")
  def content: Rep[String] = column[String]("content")

  def * : ProvenShape[File] = (
    name,
    content
  ).<>((File.apply _).tupled, File.unapply)
}

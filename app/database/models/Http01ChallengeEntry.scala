package database.models

import framework.Instant
import framework.PostgresProfile.api.*
import slick.lifted.{ProvenShape, Rep}

case class Http01ChallengeEntry(
  domain: String,
  token: String,
  content: String,
  createdAt: Instant
)

class Http01ChallengeEntryTable(tag: Tag) extends Table[Http01ChallengeEntry](tag, "http01_challenge_entry") {
  def domain: Rep[String] = column[String]("domain")
  def token: Rep[String] = column[String]("token")
  def content: Rep[String] = column[String]("content")
  def createdAt: Rep[Instant] = column[Instant]("created_at")

  def * : ProvenShape[Http01ChallengeEntry] = (
    domain,
    token,
    content,
    createdAt
  ).<>((Http01ChallengeEntry.apply _).tupled, Http01ChallengeEntry.unapply)
}

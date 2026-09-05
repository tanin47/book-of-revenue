package database.services

import database.models.{Http01ChallengeEntry, Http01ChallengeEntryTable}
import framework.{BaseDbService, Instant}
import play.api.db.slick.DatabaseConfigProvider
import slick.lifted.TableQuery

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class Http01ChallengeEntryService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
    extends BaseDbService {

  import framework.PostgresProfile.api.*

  private val query: TableQuery[Http01ChallengeEntryTable] = TableQuery[Http01ChallengeEntryTable]

  def create(domain: String, token: String, content: String): Future[Http01ChallengeEntry] = {
    val entity = Http01ChallengeEntry(
      domain = domain,
      token = token,
      content = content,
      createdAt = Instant.now()
    )

    db
      .run { query += entity }
      .map { _ => entity }
  }

  def getByToken(token: String): Future[Option[Http01ChallengeEntry]] = {
    db.run {
      query
        .filter { c => c.token === token }
        .result
        .headOption
    }
  }
}

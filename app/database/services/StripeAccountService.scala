package database.services

import database.models.{StripeAccount, StripeAccountTable}
import framework.Helpers.{makeValidationException, queueBootstrapJobs}
import framework.{BaseDbService, ExternalServiceException}
import org.jobrunr.scheduling.JobRequestScheduler
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.Json
import services.StripeService
import slick.lifted.TableQuery

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class StripeAccountService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  stripeService: StripeService,
  jobScheduler: JobRequestScheduler
)(implicit ec: ExecutionContext)
    extends BaseDbService {

  import framework.PostgresProfile.api.*

  private val query: TableQuery[StripeAccountTable] = TableQuery[StripeAccountTable]

  def getById(id: String): Future[Option[StripeAccount]] = {
    db.run {
      query.filter { a => a.id === id }.result.headOption
    }
  }

  def getFirst(): Future[Option[StripeAccount]] = {
    db.run {
      query.result.headOption
    }
  }

  def getAll(): Future[Seq[StripeAccount]] = {
    db.run {
      query.result
    }
  }

  /**
   * Upsert the account info, keeping the API key for the mode that is not being updated. For example,
   * when syncing with a test-mode key, the existing live-mode key is preserved and vice versa.
   */
  def upsert(
    id: String,
    name: String,
    defaultCurrency: String,
    isLivemode: Boolean,
    apiKey: String
  ): Future[StripeAccount] = {
    for {
      existing <- getById(id)
      entity = existing match {
        case Some(e) =>
          e.copy(
            name = name,
            defaultCurrency = defaultCurrency,
            liveModeApiKey = if (isLivemode) Some(apiKey) else e.liveModeApiKey,
            testModeApiKey = if (isLivemode) e.testModeApiKey else Some(apiKey)
          )
        case None =>
          StripeAccount(
            id = id,
            name = name,
            defaultCurrency = defaultCurrency,
            liveModeApiKey = if (isLivemode) Some(apiKey) else None,
            testModeApiKey = if (isLivemode) None else Some(apiKey)
          )
      }
      _ <- db.run {
        existing match {
          case Some(_) => query.filter { a => a.id === id }.update(entity)
          case None => query += entity
        }
      }
    } yield {
      entity
    }
  }

  def removeApiKey(id: String, isLivemode: Boolean): Future[Unit] = {
    for {
      existing <- getById(id)
      _ <- existing match {
        case Some(e) =>
          val updated = e.copy(
            liveModeApiKey = if (isLivemode) None else e.liveModeApiKey,
            testModeApiKey = if (isLivemode) e.testModeApiKey else None
          )
          if (updated.liveModeApiKey.isEmpty && updated.testModeApiKey.isEmpty) {
            db.run { query.filter { a => a.id === id }.delete }
          } else {
            db.run { query.filter { a => a.id === id }.update(updated) }
          }
        case None => Future(())
      }
    } yield {
      ()
    }
  }

  def addApiKey(apiKey: String): Future[StripeAccount] = {
    for {
      account <- stripeService.getAccount(apiKey)
        .map {
          case Some(account) => account
          case None => throw makeValidationException("validation.addNewApiKey.apiKey.error.invalid")
        }
        .recover { case e: ExternalServiceException =>
          val json = Json.parse(e.message)
          throw makeValidationException("validation.addNewApiKey.apiKey.error.externalError", (json \ "error" \ "message").as[String])
        }

      balance <- stripeService.getBalance(apiKey)
        .map {
          case Some(balance) => balance
          case None => throw makeValidationException("validation.addNewApiKey.apiKey.error.invalid")
        }
      isLivemode = (balance \ "livemode").as[Boolean]
      name = (account \ "settings" \ "dashboard" \ "display_name").asOpt[String]
        .orElse((account \ "business_profile" \ "name").asOpt[String])
        .getOrElse((account \ "id").as[String])
      stripeAccount <- upsert(
        id = (account \ "id").as[String],
        name = name,
        defaultCurrency = (account \ "default_currency").as[String],
        isLivemode = isLivemode,
        apiKey = apiKey
      )
    } yield {
      queueBootstrapJobs(jobScheduler)
      stripeAccount
    }
  }

  def deleteById(id: String): Future[Unit] = {
    db.run {
      query.filter(_.id === id).delete
    }
      .map { _ => () }
  }
}

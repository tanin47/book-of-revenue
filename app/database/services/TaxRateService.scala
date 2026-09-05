package database.services

import database.models.{TaxRate, TaxRateTable}
import framework.{BaseDbService, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object TaxRateService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    inclusive: Boolean,
    percentage: Double,
    flatAmount: Option[Long],
    flatAmountCurrency: Option[String],
    rateType: Option[String]
  )
}

@Singleton
class TaxRateService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import TaxRateService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[TaxRateTable] = TableQuery[TaxRateTable]

  def create(data: CreateData): Future[TaxRate] = {
    val entity = TaxRate(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      inclusive = data.inclusive,
      percentage = data.percentage,
      flatAmount = data.flatAmount,
      flatAmountCurrency = data.flatAmountCurrency,
      rateType = data.rateType
    )

    for {
      existing <- getById(entity.id)
      _ <- existing match {
        case Some(_) => update(entity)
        case None =>
          db
            .run { query += entity }
            .recoverWith {
              case e: PSQLException if matchUniqueConstraintException(e, "tax_rate_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: TaxRate): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getById(id: String): Future[Option[TaxRate]] = {
    getByIds(Set(id)).map(_.headOption)
  }

  def getAll(): Future[Seq[TaxRate]] = {
    db.run {
      query.result
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[TaxRate]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }
}

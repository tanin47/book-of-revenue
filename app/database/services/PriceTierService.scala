package database.services

import database.models.{PriceTier, PriceTierTable}
import framework.{BaseDbService, Instant, PlayConfig}
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object PriceTierService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    priceId: String,
    flatAmount: Option[Long],
    unitAmount: Option[Long],
    upTo: Option[Long],
    syncedAt: Instant,
  )
}

@Singleton
class PriceTierService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import PriceTierService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[PriceTierTable] = TableQuery[PriceTierTable]

  // A price tier has no natural id, so we replace all tiers for a given price.
  def replaceByPrice(priceId: String, tiers: Seq[CreateData]): Future[Seq[PriceTier]] = {
    val entities = tiers.map { tier =>
      PriceTier(
        stripeAccountId = tier.stripeAccountId,
        liveMode = tier.liveMode,
        priceId = tier.priceId,
        flatAmount = tier.flatAmount,
        unitAmount = tier.unitAmount,
        upTo = tier.upTo,
        syncedAt = tier.syncedAt
      )
    }

    val action = for {
      _ <- query.filter(_.priceId === priceId).delete
      _ <- query ++= entities
    } yield ()

    db.run(action.transactionally).map(_ => entities)
  }

  def getByPriceIds(priceIds: Set[String]): Future[Seq[PriceTier]] = {
    db.run {
      query.filter(_.priceId.inSet(priceIds)).result
    }
  }
}

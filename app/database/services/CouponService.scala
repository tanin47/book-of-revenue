package database.services

import database.models.{Coupon, CouponTable}
import framework.{BaseDbService, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object CouponService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    amountOff: Option[Long],
    currency: Option[String],
    percentOff: Option[Double],
  )
}

@Singleton
class CouponService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import CouponService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[CouponTable] = TableQuery[CouponTable]

  def create(data: CreateData): Future[Coupon] = {
    val entity = Coupon(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      amountOff = data.amountOff,
      currency = data.currency,
      percentOff = data.percentOff
    )

    for {
      existing <- getById(entity.id)
      _ <- existing match {
        case Some(_) => update(entity)
        case None =>
          db
            .run { query += entity }
            .recoverWith {
              case e: PSQLException if matchUniqueConstraintException(e, "coupon_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: Coupon): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getById(id: String): Future[Option[Coupon]] = {
    getByIds(Set(id)).map(_.headOption)
  }

  def getAll(): Future[Seq[Coupon]] = {
    db.run {
      query.result
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[Coupon]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }
}

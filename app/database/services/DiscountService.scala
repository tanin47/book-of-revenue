package database.services

import database.models.stripe.{StripeDiscount, StripeDiscountTable, RichStripeDiscount}
import framework.{BaseDbService, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object DiscountService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    couponId: Option[String]
  )
}

@Singleton
class DiscountService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  couponService: CouponService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import DiscountService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[StripeDiscountTable] = TableQuery[StripeDiscountTable]

  def create(data: CreateData): Future[StripeDiscount] = {
    val entity = StripeDiscount(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      couponId = data.couponId
    )

    for {
      existing <- getById(entity.id)
      _ <- existing match {
        case Some(_) => update(entity)
        case None =>
          db
            .run { query += entity }
            .recoverWith {
              case e: PSQLException if matchUniqueConstraintException(e, "discount_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: StripeDiscount): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getById(id: String): Future[Option[StripeDiscount]] = {
    getByIds(Set(id)).map(_.headOption)
  }

  def getAll(): Future[Seq[StripeDiscount]] = {
    db.run {
      query.result
    }
  }

  def getByIds(ids: Set[String]): Future[Seq[StripeDiscount]] = {
    db.run {
      query.filter(_.id.inSet(ids)).result
    }
  }

  def getRichByIds(ids: Set[String]): Future[Seq[RichStripeDiscount]] = {
    getByIds(ids).flatMap(hydrate)
  }

  private[this] def hydrate(items: Seq[StripeDiscount]): Future[Seq[RichStripeDiscount]] = {
    couponService.getByIds(items.flatMap(_.couponId).toSet).map { coupons =>
      val couponsById = coupons.map { c => c.id -> c }.toMap

      items.map { item =>
        RichStripeDiscount(
          base = item,
          coupon = item.couponId.flatMap(couponsById.get)
        )
      }
    }
  }
}

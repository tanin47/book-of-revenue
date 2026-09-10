package database.services

import database.models.stripe.{StripeInvoicePayment, StripeInvoicePaymentTable, RichStripeInvoicePayment}
import framework.{BaseDbService, Instant, PlayConfig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object InvoicePaymentService {
  case class CreateData(
    stripeAccountId: String = "",
    liveMode: Boolean = false,
    id: String,
    amountPaid: Option[Long],
    amountRequested: Option[Long],
    currency: String,
    invoiceId: String,
    chargeId: Option[String],
    paymentIntentId: Option[String],
    paymentRecordId: Option[String],
    paymentType: Option[String],
    createdAt: Instant,
    canceledAt: Option[Instant],
    paidAt: Option[Instant],
    status: String,
    syncedAt: Instant
  )
}

@Singleton
class InvoicePaymentService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  config: PlayConfig,
  chargeService: ChargeService,
  paymentIntentService: PaymentIntentService,
)(implicit ec: ExecutionContext) extends BaseDbService {

  import InvoicePaymentService.*
  import framework.PostgresProfile.api.*

  val query: TableQuery[StripeInvoicePaymentTable] = TableQuery[StripeInvoicePaymentTable]

  def create(data: CreateData): Future[StripeInvoicePayment] = {
    val entity = StripeInvoicePayment(
      stripeAccountId = data.stripeAccountId,
      liveMode = data.liveMode,
      id = data.id,
      amountPaid = data.amountPaid,
      amountRequested = data.amountRequested,
      currency = data.currency,
      invoiceId = data.invoiceId,
      chargeId = data.chargeId,
      paymentIntentId = data.paymentIntentId,
      paymentRecordId = data.paymentRecordId,
      paymentType = data.paymentType,
      createdAt = data.createdAt,
      canceledAt = data.canceledAt,
      paidAt = data.paidAt,
      status = data.status,
      syncedAt = data.syncedAt
    )

    for {
      existing <- getById(entity.id)
      _ <- existing match {
        case Some(_) => update(entity)
        case None =>
          db
            .run { query += entity }
            .recoverWith {
              case e: PSQLException if matchUniqueConstraintException(e, "invoice_payment_pkey") => update(entity)
            }
      }
    } yield {
      entity
    }
  }

  def update(entity: StripeInvoicePayment): Future[Unit] = {
    db
      .run {
        query.filter(_.id === entity.id).update(entity)
      }
      .map(_ => ())
  }

  def getById(id: String): Future[Option[StripeInvoicePayment]] = {
    db.run {
      query.filter(_.id === id).result.headOption
    }
  }

  def getAll(): Future[Seq[StripeInvoicePayment]] = {
    db.run {
      query.result
    }
  }

  def getByInvoice(invoiceId: String): Future[Seq[StripeInvoicePayment]] = {
    db.run {
      query.filter(_.invoiceId === invoiceId).result
    }
  }

  def getByInvoiceIds(invoiceIds: Set[String]): Future[Seq[StripeInvoicePayment]] = {
    db.run {
      query.filter(_.invoiceId.inSet(invoiceIds)).result
    }
  }

  def getRichByInvoiceIds(invoiceIds: Set[String]): Future[Seq[RichStripeInvoicePayment]] = {
    getByInvoiceIds(invoiceIds).flatMap(hydrate)
  }

  private[this] def hydrate(items: Seq[StripeInvoicePayment]): Future[Seq[RichStripeInvoicePayment]] = {
    for {
      charges <- chargeService.getRichByIds(items.flatMap(_.chargeId).toSet)
      paymentIntents <- paymentIntentService.getRichByIds(items.flatMap(_.paymentIntentId).toSet)
    } yield {
      val chargesMap = charges.map(c => c.base.id -> c).toMap
      val paymentIntentsMap = paymentIntents.map(pi => pi.base.id -> pi).toMap

      items.map { item =>
        RichStripeInvoicePayment(
          base = item,
          charge = item.chargeId.flatMap(chargesMap.get),
          paymentIntent = item.paymentIntentId.flatMap(paymentIntentsMap.get),
        )
      }
    }
  }
}

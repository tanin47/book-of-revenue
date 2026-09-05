package services

import database.services.BalanceTransactionService
import framework.{BaseDbService, Instant, Jsonable, PlayConfig}
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.{JsObject, Json}
import process.Helpers.{getAccountingPeriod, getNextAccountingPeriod}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object ExchangeRate {
  def sameCurrency(currency: String): ExchangeRate = ExchangeRate(currency, currency, 1L, 1L)
}

case class ExchangeRate(
  baseCurrency: String,
  exchangeCurrency: String,
  baseAmount: Long,
  exchangeAmount: Long,
) extends Jsonable {
  def exchange(amount: Long): Long = if (baseCurrency == exchangeCurrency) {
    amount
  } else {
    (amount.toDouble * exchangeAmount / baseAmount).round
  }

  def toJson(): JsObject = Json.obj(
    "baseCurrency" -> baseCurrency,
    "exchangeCurrency" -> exchangeCurrency,
    "rate" -> (exchangeAmount.toDouble / baseAmount.toDouble),
  )
}

@Singleton
class ExchangeRateService @Inject() (
  val dbConfigProvider: DatabaseConfigProvider,
  balanceTransactionService: BalanceTransactionService,
  config: PlayConfig,
)(implicit ec: ExecutionContext) extends BaseDbService {
  import framework.PostgresProfile.api.*

  def get(
    balanceTransactionId: Option[String],
    baseCurrency: String,
    exchangeCurrency: String,
    timestamp: Instant
  ): Future[ExchangeRate] = {
    if (baseCurrency == exchangeCurrency) { return Future.successful(ExchangeRate.sameCurrency(baseCurrency)) }

    val periodStart = getAccountingPeriod(timestamp)
    val periodEnd = getNextAccountingPeriod(timestamp)

    for {
      exchangeRate <- balanceTransactionId match {
        case Some(btId) => balanceTransactionService.getRichById(balanceTransactionId.get).map { bt =>
          (bt, bt.flatMap(_.charge)) match {
            // Only if the BT is within the same period.
            case (Some(bt), Some(charge)) if periodStart.getEpochSecond <= bt.base.createdAt.getEpochSecond && bt.base.createdAt.getEpochSecond < periodEnd.getEpochSecond => Some(ExchangeRate(baseCurrency, exchangeCurrency, charge.amount, bt.base.amount))
            case _ => None
          }
        }
        case None => Future(None)
      }
      exchangeRate <- exchangeRate match {
        case Some(rate) => Future(Some(rate))
        case None => estimateExchangeRate(baseCurrency, exchangeCurrency, timestamp)
      }
    } yield {
      exchangeRate.getOrElse(ExchangeRate.sameCurrency(baseCurrency))
    }

  }

  def estimateExchangeRate(
    sourceCurrency: String,
    exchangeCurrency: String,
    timestamp: Instant,
  ): Future[Option[ExchangeRate]] = {
    val periodStart = getAccountingPeriod(timestamp)
    val periodEnd = getNextAccountingPeriod(timestamp)

    def get(
      windowStart: Option[Instant],
      windowExclusiveEnd: Option[Instant],
    ): Future[Option[ExchangeRate]] = {
      estimateExchangeRateWithinTimeframe(
        sourceCurrency = sourceCurrency,
        exchangeCurrency = exchangeCurrency,
        timestamp = timestamp,
        windowStart = windowStart,
        windowExclusiveEnd = windowExclusiveEnd
      )
    }

    for {
      rate <- get(windowStart = Some(periodStart), windowExclusiveEnd = Some(periodEnd))
      rate <- rate match {
        case Some(rate) => Future(Some(rate))
        case None => get(windowStart = None, windowExclusiveEnd = Some(periodEnd))
      }
      rate <- rate match {
        case Some(rate) => Future(Some(rate))
        case None => get(windowStart = None, windowExclusiveEnd = None)
      }
    } yield {
      rate
    }
  }

  def estimateExchangeRateWithinTimeframe(
    sourceCurrency: String,
    exchangeCurrency: String,
    timestamp: Instant,
    windowStart: Option[Instant],
    windowExclusiveEnd: Option[Instant],
  ): Future[Option[ExchangeRate]] = {
    val whereSql = joinSqls(
      Seq(
        Some(sql"charge.currency = $sourceCurrency"),
        Some(sql"balance_transaction.currency = $exchangeCurrency"),
        windowStart.map { start => sql"balance_transaction.created_at >= $start" },
        windowExclusiveEnd.map { exclusiveEnd => sql"balance_transaction.created_at < $exclusiveEnd" },
      ).flatten,
      sql" AND "
    )

    db.run {
      makeSql(
        sql"""
          SELECT
            charge.amount,
            balance_transaction.amount
          FROM charge
          JOIN balance_transaction ON balance_transaction.id = charge.balance_transaction_id
          WHERE
        """,
        whereSql,
        sql"""
          ORDER BY ABS(EXTRACT(EPOCH FROM balance_transaction.created_at) - ${timestamp.getEpochSecond}) ASC
          LIMIT 1
        """
      ).as[(Long, Long)]
    }
      .map { items =>
        items.headOption.map { case (baseAmount, exchangeAmount) =>
          ExchangeRate(sourceCurrency, exchangeCurrency, baseAmount, exchangeAmount)
        }
      }
  }
}

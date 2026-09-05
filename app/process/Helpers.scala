package process

import background.ProcessTransactionWorker.{AmortizationPeriod, JournalEntryPeriod}
import database.models.JournalEntry
import framework.Instant
import process.ProcessBillingEvent.{Amount, DualAmount, JournalEntryAmount}

import java.time.{ZoneOffset, ZonedDateTime}
import java.util.Currency
import scala.collection.mutable.ListBuffer

object Helpers {
  def amortize4(amount: JournalEntryAmount, weights: Seq[Long], baseline: DualAmount = DualAmount(0, 0)): Seq[JournalEntryAmount] = {
    amortize2(amount.toDualAmount(), weights.map { weight => DualAmount(weight, weight) }, baseline)
      .map { a =>
        JournalEntryAmount(
          settlement = Amount(a.settlement, amount.settlement.currency),
          presentment = Amount(a.presentment, amount.presentment.currency)
        )
      }
  }
  def amortize3(amount: DualAmount, weights: Seq[Long], baseline: DualAmount = DualAmount(0, 0)): Seq[DualAmount] = {
    amortize2(amount, weights.map { weight => DualAmount(weight, weight) }, baseline)
  }
  def amortize2(amount: DualAmount, weights: Seq[DualAmount], baseline: DualAmount = DualAmount(0, 0)): Seq[DualAmount] = {
    val settlementAmounts = amortize(amount.settlement, weights.map(_.settlement), baseline.settlement)
    val presentmentAmounts = amortize(amount.presentment, weights.map(_.presentment), baseline.presentment)

    settlementAmounts.zip(presentmentAmounts).map { case (settlementAmount, presentmentAmount) =>
      DualAmount(settlement = settlementAmount, presentment = presentmentAmount)
    }
  }
  def amortize(amount: Long, weights: Seq[Long], baseline: Long = 0L): Seq[Long] = {
    if (weights.isEmpty) { throw new Exception("Cannot amortize " + amount + " with empty weights") }
    if (weights.forall(_ == 0)) { return amortize(amount, weights.map(_ => 1L), baseline) }

    if (amount < 0) {
      return amortize(-amount, weights.map(-_), -baseline).map { principle => -principle }
    }

    val principle = amount.toDouble / weights.sum
    val principles = weights.map { weight => (principle * weight).toLong }

    var totalRemainder = amount - principles.sum
    val remainderMultiplier = totalRemainder / weights.size
    var remainderRemainder = totalRemainder % weights.size
    val addRemainderEveryN = if (remainderRemainder == 0) { 0 } else { weights.size / remainderRemainder }

    principles.zipWithIndex.map { case (principle, index) =>
      // so we stuff the remainder at the front? We have to spread it out.
      // We have to spread it out instead
      val remainder = if (addRemainderEveryN > 0 && ((baseline + index) % addRemainderEveryN) == 0 && remainderRemainder > 0) {
        remainderRemainder -= 1
        1
      } else {
        0
      }
      principle + remainderMultiplier + remainder
    }.toList
  }

  def fillFromTheFront(amount: DualAmount, periods: Seq[JournalEntryPeriod]): Seq[JournalEntryPeriod] = {
    var remainingSettlement = amount.settlement
    var remainingPresentment = amount.presentment

    val result = periods.map { period =>
      val filledSettlement = Math.min(remainingSettlement, period.settlementAmount)
      val filledPresentment = Math.min(remainingPresentment, period.presentmentAmount)
      remainingSettlement -= filledSettlement
      remainingPresentment -= filledPresentment

      period.copy(settlementAmount = filledSettlement, presentmentAmount = filledPresentment)
    }

// TODO: Think whether we need this. It has issues with negative amounts.
//    if (remainingSettlement > 0 || remainingPresentment > 0) {
//      throw new Exception("Too much amount to fill from the front: " + remainingSettlement + ", " + remainingPresentment + " (" + periods.map(_.settlementAmount).sum + ", " + periods.map(_.presentmentAmount).sum + ")")
//    }

    result
  }

  def fillFromTheBack(amount: DualAmount, periods: Seq[JournalEntryPeriod]): Seq[JournalEntryPeriod] = {
    var remainingSettlement = amount.settlement
    var remainingPresentment = amount.presentment

    val result = periods
      .reverse
      .map { period =>
        val filledSettlement = Math.min(remainingSettlement, period.settlementAmount)
        val filledPresentment = Math.min(remainingPresentment, period.presentmentAmount)
        remainingSettlement -= filledSettlement
        remainingPresentment -= filledPresentment

        period.copy(settlementAmount = filledSettlement, presentmentAmount = filledPresentment)
      }
      .reverse

// TODO: Think whether we need this. It has issues with negative amounts.
//    if (remainingSettlement > 0 || remainingPresentment > 0) {
//      throw new Exception("Too much amount to fill from the back. Remaining: (" + remainingSettlement + ", " + remainingPresentment + "). Filled: (" + periods.map(_.settlementAmount).sum + ", " + periods.map(_.presentmentAmount).sum + ")")
//    }

    result
  }

  def sumAccountCategory2(
    entries: Seq[JournalEntry],
    category: JournalEntry.AccountCategory,
    settlementCurrency: String,
    presentmentCurrency: String
  ):JournalEntryAmount = {
    sumAccountCategory(entries, category).getOrElse(JournalEntryAmount.empty(settlementCurrency, presentmentCurrency))
  }

  def sumAccountCategory(
    entries: Seq[JournalEntry],
    category: JournalEntry.AccountCategory
  ): Option[JournalEntryAmount] = {
    if (entries.isEmpty) {
      return None
    }

    val settlementCurrency = entries.head.settlementCurrency
    val presentmentCurrency = entries.head.presentmentCurrency

    Some(
      entries
        .map { e =>
          val bare = JournalEntryAmount(Amount(e.settlementAmount, settlementCurrency), Amount(e.presentmentAmount, presentmentCurrency))

          Some(
            if (e.debit.getAccountCategory() == category) {
              bare
            } else if (e.credit.getAccountCategory() == category) {
              -bare
            } else {
              JournalEntryAmount(Amount(0L, settlementCurrency), Amount(0L, presentmentCurrency))
            }
          )
            .map { amount =>
              if (category.isCredit()) { -amount } else { amount }
            }
            .get
        }
        .reduce(_ + _)
    )
  }

  def sumAccount2(
    entries: Seq[JournalEntry],
    account: JournalEntry.Account,
    settlementCurrency: String,
    presentmentCurrency: String
  ): JournalEntryAmount = {
    sumAccount(entries, account).getOrElse(JournalEntryAmount.empty(settlementCurrency, presentmentCurrency))
  }

  def sumAccount(entries: Seq[JournalEntry], account: JournalEntry.Account): Option[JournalEntryAmount] = {
    if (entries.isEmpty) {
      return None
    }

    val settlementCurrency = entries.head.settlementCurrency
    val presentmentCurrency = entries.head.presentmentCurrency

    Some(
      entries
        .map { e =>
          val bare = JournalEntryAmount(Amount(e.settlementAmount, settlementCurrency), Amount(e.presentmentAmount, presentmentCurrency))

          Some(
            if (e.debit == account) {
              bare
            } else if (e.credit == account) {
              -bare
            } else {
              JournalEntryAmount(Amount(0L, settlementCurrency), Amount(0L, presentmentCurrency))
            }
          )
            .map { amount =>
              if (account.isCredit()) { -amount } else { amount }
            }
            .get
        }
        .reduce(_ + _)
    )
  }

  enum ContraMode extends Enum[ContraMode] {
    case Paid, BackToFront, UniformUnpaid
  }

  def computeContraPeriods(
    contraAmount: DualAmount,
    paidRevenueAmount: DualAmount,
    bookedRevenuePeriods: Seq[JournalEntryPeriod],
    mode: ContraMode
  ): Seq[JournalEntryPeriod] = {
    mode match {
      case ContraMode.Paid =>
        Some(bookedRevenuePeriods)
          .map { periods => fillFromTheFront(paidRevenueAmount, periods) }
          .map { periods => fillFromTheBack(contraAmount, periods) }
          .get
      case ContraMode.BackToFront => fillFromTheBack(contraAmount, bookedRevenuePeriods)
      case ContraMode.UniformUnpaid =>
        val paidPeriods = fillFromTheFront(paidRevenueAmount, bookedRevenuePeriods)
        val unpaidPeriods = bookedRevenuePeriods.zip(paidPeriods).map { case (booked, paid) =>
          booked.copy(
            settlementAmount = booked.settlementAmount -paid.settlementAmount,
            presentmentAmount = booked.presentmentAmount - paid.presentmentAmount
          )
        }
        val amortizedAmounts = amortize2(contraAmount, unpaidPeriods.map { p => DualAmount(p.settlementAmount, p.presentmentAmount)})

        unpaidPeriods.zip(amortizedAmounts).map { case (unpaid, amortized) =>
          unpaid.copy(
            settlementAmount = amortized.settlement,
            presentmentAmount = amortized.presentment
          )
        }
    }
  }

  def amortize(amount: Long, startedAt: Instant, endedAt: Instant): Seq[AmortizationPeriod] = {
    if (amount < 0) {
      val periods = amortize(-amount, startedAt, endedAt)
      return periods.map { p => p.copy(amount = -p.amount) }
    }

    val periods = generatePeriods(startedAt, endedAt)
    val amounts = amortize(
      amount,
      periods.map { p => Instant.min(p.endedAt, endedAt).toEpochMilli - Instant.max(p.startedAt, startedAt).toEpochMilli }
    )

    periods.zip(amounts).map { case (period, amount) =>
      period.copy(amount = amount)
    }
  }

  def floorMonth(z: ZonedDateTime): ZonedDateTime = z.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0)

  def getAccountingPeriod(time: Instant): Instant = {
    floorMonth(ZonedDateTime.ofInstant(time, ZoneOffset.UTC)).toInstant
  }

  def getNextAccountingPeriod(time: Instant): Instant = {
    floorMonth(ZonedDateTime.ofInstant(time, ZoneOffset.UTC).plusMonths(1)).toInstant
  }

  def generatePeriods(startedAt: Instant, exclusiveEndedAt: Instant): Seq[AmortizationPeriod] = {
    val periods = ListBuffer.empty[AmortizationPeriod]

    var current = floorMonth(ZonedDateTime.ofInstant(startedAt, ZoneOffset.UTC))

    if (startedAt == exclusiveEndedAt) {
      return Seq(AmortizationPeriod(startedAt = current.toInstant, endedAt = current.plusMonths(1).toInstant, amount = 0))
    }

    while (current.toInstant.isBefore(exclusiveEndedAt)) {
      val next = current.plusMonths(1)
      periods.addOne(AmortizationPeriod(
        startedAt = current.toInstant,
        endedAt = next.toInstant,
        amount = 0
      ))

      current = next
    }

    periods.toSeq

  }

  def getCurrencySymbol(currency: String): String = {
    Currency.getInstance(currency.toUpperCase).getSymbol
  }

  def formatAmount(amount: Long, currency: String, includeSign: Boolean): String = {
    val sign = if (amount < 0) { "-" } else {
      if (includeSign) { "+" } else { "" }
    }
    s"${sign}${getCurrencySymbol(currency)}${"%.2f".format(Math.abs(amount).toDouble / 100)}"
  }
}

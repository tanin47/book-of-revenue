package process

import database.models.*
import database.models.stripe.*
import framework.Instant
import process.Helpers.{amortize, generatePeriods}
import process.ProcessBillingEvent.{Amount, JournalEntryAmount}
import services.ExchangeRate

object ProcessUnbilledUsageSubscriptionItem {
  def generateUsageBasedJournalEntries(
    startedAt: Instant,
    endedAt: Instant,
    price: RichStripePrice,
    meterEventSummaries: Seq[StripeMeterEventSummary],
    exchangeRate: ExchangeRate,
    discounts: Seq[RichStripeDiscount],
    taxRates: Seq[StripeTaxRate],
    invoiceLineItemTotalDiscountAmount: Long,
    invoiceLineItemTotalCreditGrantAmount: Long,
    invoiceLineItemTotalInclusiveTaxAmount: Long
  ): Seq[JournalEntry] = {
    var cumulativeAggregatedValue = 0L
    var previousCumulativeRevenue = 0L
    val periods = generatePeriods(startedAt, endedAt)
      .map { period =>
        val aggregatedValue = getAggregatedValue(
          startedAt = Instant.max(period.startedAt, startedAt),
          endedAt = Instant.min(period.endedAt, endedAt),
          meterEventSummaries = meterEventSummaries
        )
        cumulativeAggregatedValue += aggregatedValue
        val revenue = computeNetRevenue(
          amount = computeRevenue(cumulativeAggregatedValue, price),
          discounts = discounts,
          taxRates = taxRates,
        )
        val newRevenue = revenue - previousCumulativeRevenue
        previousCumulativeRevenue = revenue
        period.copy(
          amount = newRevenue
        )
      }

    val discountPeriods = amortize(invoiceLineItemTotalDiscountAmount, periods.map(_.amount)).toArray
    val creditGrantPeriods = amortize(invoiceLineItemTotalCreditGrantAmount, periods.map(_.amount)).toArray
    val inclusiveTaxPeriods = amortize(invoiceLineItemTotalInclusiveTaxAmount, periods.map(_.amount)).toArray

    val usageBasedEntries = periods.zipWithIndex.map { case (period, index) =>
      val amount = period.amount - discountPeriods(index) - creditGrantPeriods(index) - inclusiveTaxPeriods(index)
      JournalEntry(
        accountingPeriod = period.startedAt,
        attributionPeriod = None,
        debit = JournalEntry.Account.UnbilledAccountsReceivable,
        credit = JournalEntry.Account.Revenue,
        settlementAmount = exchangeRate.exchange(amount),
        settlementCurrency = exchangeRate.exchangeCurrency,
        presentmentAmount = amount,
        presentmentCurrency = price.base.currency,
        occurredAt = Instant.min(period.endedAt, endedAt),
        event = JournalEntry.Event.RecognizeRevenue,
        reversedEvent = None,
        principleAccount = JournalEntry.Account.Revenue,
        stripeAccountId = null,
        liveMode = false,
        transactionId = null,
        transactionType = null,
        stripeCustomerId = None,
        stripeInvoiceId = None,
        stripeInvoiceLineItemId = None,
        stripeInvoiceItemId = None,
        stripeChargeId = None,
        stripeBalanceTransactionId = None,
        stripeDisputeId = None,
        stripeRefundId = None,
        stripeCustomerBalanceTransactionId = None,
        stripePaymentIntentId = None,
        stripePaymentRecordId = None,
        stripeSubscriptionId = None,
        stripeSubscriptionItemId = None,
        stripeCreditBalanceTransactionId = None,
        stripeCreditNoteId = None,
        stripeCreditNoteLineItemId = None,
        stripeProductId = Some(price.base.productId),
        stripePriceId = Some(price.base.id),
        createdAt = null
      )
    }

    usageBasedEntries
  }

  def generateFlatFeeJournalEntries(
    startedAt: Instant,
    endedAt: Instant,
    price: RichStripePrice,
    meterEventSummaries: Seq[StripeMeterEventSummary],
    exchangeRate: ExchangeRate,
    subscriptionItem: RichStripeSubscriptionItem
  ): Seq[JournalEntry] = {

    val discounts = subscriptionItem.discounts ++ subscriptionItem.subscription.discounts
    val taxRates = Some(subscriptionItem.taxRates).filter(_.nonEmpty).getOrElse(subscriptionItem.subscription.defaultTaxRates)

    val flatFeePresentmentAmount = computeNetRevenue(
      amount = computeFlatFee(
        aggregatedValue = getAggregatedValue(startedAt = startedAt, endedAt = endedAt, meterEventSummaries = meterEventSummaries),
        price = price
      ),
      discounts = discounts,
      taxRates = taxRates,
    )
    val flatFeeEntries = ProcessUnbilledInvoiceItem.generateJournalEntries(
      startedAt = startedAt,
      endedAt = endedAt,
      amount = JournalEntryAmount(
        settlement = Amount(exchangeRate.exchange(flatFeePresentmentAmount), exchangeRate.exchangeCurrency),
        presentment = Amount(flatFeePresentmentAmount, price.base.currency),
      ),
      occurredAt = startedAt
    )

    flatFeeEntries
  }

  private[this] def computeNetRevenue(
    amount: Long,
    discounts: Seq[RichStripeDiscount],
    taxRates: Seq[StripeTaxRate],
  ): Long = {
    val discount = discounts.map(_.computeDiscount(amount)).sum
    val subtotal = amount - discount
    val totalInclusiveTaxAmount = taxRates.filter(_.inclusive).map(_.computeTax(subtotal)).sum

    subtotal - totalInclusiveTaxAmount
  }

  private[this] def computeFlatFee(
    aggregatedValue: Long,
    price: RichStripePrice,
  ): Long = {
    price.base.billingScheme match {
      case "per_unit" => 0L
      case "tiered" =>
        val (tiers, _) = price.tiers.splitAt(price.tiers.indexWhere(_.upTo.forall(aggregatedValue <= _)) + 1)
        price.base.tiersMode.get match {
          case "volume" => tiers.last.flatAmount.getOrElse(0L)
          case "graduated" => tiers.map(_.flatAmount.getOrElse(0L)).sum
        }
    }
  }

  private[this] def computeRevenue(aggregatedValue: Long, price: RichStripePrice): Long = {
    price.base.billingScheme match {
      case "per_unit" => price.base.unitAmount * aggregatedValue
      case "tiered" =>
        val (tiers, _) = price.tiers.splitAt(price.tiers.indexWhere(_.upTo.forall(aggregatedValue <= _)) + 1)
        price.base.tiersMode.get match {
          case "volume" => tiers.last.unitAmount.get * aggregatedValue
          case "graduated" =>
            var remaining = aggregatedValue
            tiers
              .map { tier =>
                val value = Math.min(remaining, tier.upTo.getOrElse(remaining))
                remaining -= value
                tier.unitAmount.get * value
              }
              .sum
        }
    }
  }

  private[this] def getAggregatedValue(startedAt: Instant, endedAt: Instant, meterEventSummaries: Seq[StripeMeterEventSummary]): Long = {
    meterEventSummaries
      .filter { summary =>
        // If overlapping at all, we count it. We may double count, and that's fine.
        (summary.startTime == startedAt && summary.endTime == endedAt) ||
          (startedAt.isBefore(summary.startTime) && summary.startTime.isBefore(endedAt)) ||
          (startedAt.isBefore(summary.endTime) && summary.endTime.isBefore(endedAt))
      }
      .map(_.aggregatedValue)
      .sum
  }
}

case class ProcessUnbilledUsageSubscriptionItem(
  transaction: Transaction,
  subscriptionItem: RichStripeSubscriptionItem,
) extends ProcessTransaction {
  lazy val syncedAt: Instant = subscriptionItem.syncedAt
  lazy val startedAt: Option[Instant] = Some(subscriptionItem.base.currentPeriodStart)
  lazy val status: Transaction.Status = Transaction.Status.Open

  def generateRawJournalEntries(): Seq[JournalEntry] = {
    val usageBasedEntries = ProcessUnbilledUsageSubscriptionItem.generateUsageBasedJournalEntries(
      startedAt = subscriptionItem.base.currentPeriodStart,
      endedAt = subscriptionItem.base.currentPeriodEnd,
      price = subscriptionItem.price.get,
      meterEventSummaries = subscriptionItem.meterEventSummaries,
      exchangeRate = subscriptionItem.currentPeriodStartExchangeRate,
      discounts = subscriptionItem.discounts,
      taxRates = Some(subscriptionItem.taxRates).filter(_.nonEmpty).getOrElse(subscriptionItem.subscription.defaultTaxRates),
      invoiceLineItemTotalDiscountAmount = 0L,
      invoiceLineItemTotalCreditGrantAmount = 0L,
      invoiceLineItemTotalInclusiveTaxAmount = 0L
    )
    val flatFeeEntries = ProcessUnbilledUsageSubscriptionItem.generateFlatFeeJournalEntries(
      startedAt = subscriptionItem.base.currentPeriodStart,
      endedAt = subscriptionItem.base.currentPeriodEnd,
      price = subscriptionItem.price.get,
      meterEventSummaries = subscriptionItem.meterEventSummaries,
      exchangeRate = subscriptionItem.currentPeriodStartExchangeRate,
      subscriptionItem = subscriptionItem
    )


    (usageBasedEntries ++ flatFeeEntries)
      .map { entry =>
        entry.copy(
          stripeAccountId = transaction.stripeAccountId,
          liveMode = transaction.liveMode,
          transactionId = transaction.id,
          transactionType = transaction.tpe,
          stripeCustomerId = Some(subscriptionItem.subscription.base.customerId),
          stripeSubscriptionId = Some(subscriptionItem.base.subscriptionId),
          stripeSubscriptionItemId = Some(subscriptionItem.base.id),
          stripeProductId = subscriptionItem.price.map(_.base.productId),
          stripePriceId = Some(subscriptionItem.base.priceId),
          createdAt = syncedAt
        )
      }
  }
}

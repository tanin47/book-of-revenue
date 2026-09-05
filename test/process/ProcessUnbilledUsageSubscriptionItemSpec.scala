package process

import base.Base
import database.models.*
import database.models.JournalEntry.Account.*
import framework.{Instant, NetAmount}
import services.ExchangeRate

class ProcessUnbilledUsageSubscriptionItemSpec extends Base {
  it("books unbilled usage as revenue") {
    val subscriptionItemBase = makeSubscriptionItem(
      currentPeriodStart = Instant.parse("2026-01-14T00:00:00Z"),
      currentPeriodEnd = Instant.parse("2026-02-20T00:00:00Z"),
    )

    val transaction = makeProcessUnbilledUsageSubscriptionItem(
      transaction = makeRevRecTransaction(tpe = RevRecTransaction.Type.UnbilledUsageSubscriptionItem),
      subscriptionItem = makeRichSubscriptionItem(
        base = subscriptionItemBase,
        price = Some(makeRichPrice(base = makePrice(billingScheme = "per_unit", unitAmount = 5, currency = "usd"))),
        meterEventSummaries = Seq(
          makeMeterEventSummary(aggregatedValue = 1, startTime = Instant.parse("2026-01-08T00:00:00Z"), endTime = Instant.parse("2026-01-09T00:00:00Z")),
          makeMeterEventSummary(aggregatedValue = 100, startTime = Instant.parse("2026-01-16T00:00:00Z"), endTime = Instant.parse("2026-01-17T00:00:00Z")),
          makeMeterEventSummary(aggregatedValue = 50, startTime = Instant.parse("2026-02-16T00:00:00Z"), endTime = Instant.parse("2026-02-17T00:00:00Z")),
          makeMeterEventSummary(aggregatedValue = 2, startTime = Instant.parse("2026-02-23T00:00:00Z"), endTime = Instant.parse("2026-02-24T00:00:00Z"))
        ),
        currentPeriodStartExchangeRate = ExchangeRate.sameCurrency("usd")
      )
    )

    val entries = transaction.generateRawJournalEntries()
    NetAmount.compute(entries, endPeriod = Some(Instant.parse("2026-02-01T00:00:00Z"))) should be(Seq(
      NetAmount(750, Revenue),
      NetAmount(750, UnbilledAccountsReceivable),
    ))
    NetAmount.compute(entries) should be(Seq(
      NetAmount(750, Revenue),
      NetAmount(750, UnbilledAccountsReceivable),
    ))
  }

  it("books unbilled usage settled in a different currency") {
    val periodStart = Instant.parse("2026-01-14T00:00:00Z")
    val transaction = makeProcessUnbilledUsageSubscriptionItem(
      transaction = makeRevRecTransaction(tpe = RevRecTransaction.Type.UnbilledUsageSubscriptionItem),
      subscriptionItem = makeRichSubscriptionItem(
        base = makeSubscriptionItem(currentPeriodStart = periodStart, currentPeriodEnd = Instant.parse("2026-02-20T00:00:00Z")),
        price = Some(makeRichPrice(base = makePrice(billingScheme = "per_unit", unitAmount = 5, currency = "usd"))),
        meterEventSummaries = Seq(makeMeterEventSummary(aggregatedValue = 100, startTime = periodStart, endTime = Instant.parse("2026-02-01T00:00:00Z"))),
        currentPeriodStartExchangeRate = ExchangeRate("usd", "eur", 100, 90)
      )
    )

    val entries = transaction.generateRawJournalEntries()
    NetAmount.compute(entries, endPeriod = Some(Instant.parse("2026-02-01T00:00:00Z"))) should be(Seq(
      NetAmount(450, Revenue, "eur"),
      NetAmount(450, UnbilledAccountsReceivable, "eur"),
    ))
    NetAmount.compute(entries) should be(Seq(
      NetAmount(450, Revenue, "eur"),
      NetAmount(450, UnbilledAccountsReceivable, "eur"),
    ))
  }

  it("books unbilled usage with a flat fee") {
    val transaction = makeProcessUnbilledUsageSubscriptionItem(
      transaction = makeRevRecTransaction(tpe = RevRecTransaction.Type.UnbilledUsageSubscriptionItem),
      subscriptionItem = makeRichSubscriptionItem(
        base = makeSubscriptionItem(
          currentPeriodStart = Instant.parse("2026-01-01T00:00:00Z"),
          currentPeriodEnd = Instant.parse("2026-03-01T00:00:00Z"),
        ),
        price = Some(makeRichPrice(
          base = makePrice(billingScheme = "tiered", tiersMode = Some("volume"), currency = "usd"),
          // A flat fee of 1180 plus 5 per usage unit.
          tiers = Seq(makePriceTier(flatAmount = Some(1180), unitAmount = Some(5), upTo = Some(1000)))
        )),
        meterEventSummaries = Seq(
          makeMeterEventSummary(aggregatedValue = 100, startTime = Instant.parse("2026-01-10T00:00:00Z"), endTime = Instant.parse("2026-01-11T00:00:00Z")),
          makeMeterEventSummary(aggregatedValue = 50, startTime = Instant.parse("2026-02-10T00:00:00Z"), endTime = Instant.parse("2026-02-11T00:00:00Z")),
        ),
        // A 10% discount and a 10% inclusive tax, applied to both usage revenue and the flat fee.
        discounts = Seq(makeRichDiscount(coupon = Some(makeCoupon(percentOff = Some(10))))),
        taxRates = Seq(makeTaxRate(inclusive = true, rateType = Some("percentage"), percentage = 10)),
        currentPeriodStartExchangeRate = ExchangeRate.sameCurrency("usd")
      )
    )

    val entries = transaction.generateRawJournalEntries()

    // Before February: January usage (500 gross, less 50 discount and 40 inclusive tax = 410) is
    // recognized immediately, and the January portion of the amortized net flat fee (966 * 31/59 = 508)
    // is recognized while the rest (458) stays in unbilled deferred revenue.
    NetAmount.compute(entries, endPeriod = Some(Instant.parse("2026-01-31T00:00:00Z"))) should be(Seq(
      NetAmount(918, Revenue),
      NetAmount(1376, UnbilledAccountsReceivable),
      NetAmount(458, UnbilledDeferredRevenue),
    ))
    NetAmount.compute(entries) should be(Seq(
      NetAmount(1580, Revenue),
      NetAmount(1580, UnbilledAccountsReceivable),
    ))
  }
}

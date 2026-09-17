package process

import base.Base
import database.models.*
import database.models.JournalEntry.Account.*
import framework.{Instant, NetAmount}
import play.api.libs.json.Json
import services.ExchangeRate

class ProcessMetronomeDraftInvoiceSpec extends Base {
  it("books unbilled usage as revenue (same currency)") {
    val lineItems = Seq(
      makeMetronomeDraftLineItem(name = Some("API usage"), total = Some(BigDecimal(1000))),
      makeMetronomeDraftLineItem(
        name = Some("Credit purchase"),
        total = Some(BigDecimal(500)),
        metadata = Some(Json.obj("commit_type" -> "PrepaidCommit").toString)
      ),
      makeMetronomeDraftLineItem(
        name = Some("Credit applied"),
        total = Some(BigDecimal(-300)),
      ),
    )
    val metronomeInvoice = makeRichMetronomeDraftInvoice(
      lineItems = lineItems,
      breakdownInvoice = Some(makeRichMetronomeBreakdownDraftInvoice(
        lineItems = Seq(
          makeMetronomeBreakdownDraftLineItem(
            lineItemId = Some(lineItems(0).id),
            total = Some(BigDecimal(400)),
            breakdownStartTimestamp = Some(Instant.parse("2025-08-01T00:00:00Z")),
            breakdownEndTimestamp = Some(Instant.parse("2025-08-30T00:00:00Z")),
          ),
          makeMetronomeBreakdownDraftLineItem(
            lineItemId = Some(lineItems(0).id),
            total = Some(BigDecimal(600)),
            breakdownStartTimestamp = Some(Instant.parse("2025-09-01T00:00:00Z")),
            breakdownEndTimestamp = Some(Instant.parse("2025-09-02T00:00:00Z")),
          ),
          makeMetronomeBreakdownDraftLineItem(
            lineItemId = Some(lineItems(2).id),
            total = Some(BigDecimal(-200)),
            breakdownStartTimestamp = Some(Instant.parse("2025-08-01T00:00:00Z")),
            breakdownEndTimestamp = Some(Instant.parse("2025-09-01T00:00:00Z")),
            lineItemType = Some("applied_commit_or_credit")
          ),
          makeMetronomeBreakdownDraftLineItem(
            lineItemId = Some(lineItems(2).id),
            total = Some(BigDecimal(-100)),
            breakdownStartTimestamp = Some(Instant.parse("2025-09-01T00:00:00Z")),
            breakdownEndTimestamp = Some(Instant.parse("2025-10-01T00:00:00Z")),
            lineItemType = Some("applied_commit_or_credit")
          ),
        )
      ))
    )

    val transaction = ProcessMetronomeDraftInvoice(
      transaction = makeTransaction(tpe = Transaction.Type.UnbilledUsageSubscriptionItem),
      invoice = metronomeInvoice,
    )

    val entries = transaction.generateRawJournalEntries()
    NetAmount.compute(entries, endPeriod = Some(Instant.parse("2025-08-01T00:00:00Z"))) should be(Seq(
      NetAmount(400, Revenue),
      NetAmount(400, UnbilledAccountsReceivable),
    ))
    NetAmount.compute(entries) should be(Seq(
      NetAmount(1000, Revenue),
      NetAmount(1000, UnbilledAccountsReceivable),
    ))
  }

//  it("books unbilled usage as revenue (different currencies)") {
//    val lineItems = Seq(
//      makeMetronomeDraftLineItem(name = Some("API usage"), total = Some(BigDecimal(1000))),
//    )
//    val metronomeInvoice = makeRichMetronomeDraftInvoice(
//      lineItems = lineItems,
//      breakdownInvoice = Some(makeRichMetronomeBreakdownDraftInvoice(
//        lineItems = Seq(
//          makeMetronomeBreakdownDraftLineItem(
//            lineItemId = Some(lineItems(0).id),
//            total = Some(BigDecimal(400)),
//            breakdownStartTimestamp = Some(Instant.parse("2025-08-01T00:00:00Z")),
//            breakdownEndTimestamp = Some(Instant.parse("2025-08-30T00:00:00Z")),
//          ),
//          makeMetronomeBreakdownDraftLineItem(
//            lineItemId = Some(lineItems(0).id),
//            total = Some(BigDecimal(600)),
//            breakdownStartTimestamp = Some(Instant.parse("2025-09-01T00:00:00Z")),
//            breakdownEndTimestamp = Some(Instant.parse("2025-09-02T00:00:00Z")),
//          ),
//        )
//      ))
//    )
//
//    val transaction = ProcessMetronomeDraftInvoice(
//      transaction = makeTransaction(tpe = Transaction.Type.UnbilledUsageSubscriptionItem),
//      invoice = metronomeInvoice,
//      exchangeRate = ExchangeRate("usd", "thb", 100, 3000)
//    )
//
//    val entries = transaction.generateRawJournalEntries()
//    NetAmount.compute(entries, endPeriod = Some(Instant.parse("2025-08-01T00:00:00Z"))) should be(Seq(
//      NetAmount(12000, Revenue, "thb"),
//      NetAmount(12000, UnbilledAccountsReceivable, "thb"),
//    ))
//    NetAmount.compute(entries) should be(Seq(
//      NetAmount(30000, Revenue, "thb"),
//      NetAmount(30000, UnbilledAccountsReceivable, "thb"),
//    ))
//  }
}

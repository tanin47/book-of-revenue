package process

import base.Base
import database.models.*
import database.models.JournalEntry.Account.*
import framework.{Instant, NetAmount}
import play.api.libs.json.Json

class ProcessMetronomeInvoiceSpec extends Base {
  it("books revenue according to metronome's breakdown line items") {
    val now = Instant.parse("2025-09-02T00:00:00Z")
    val invoice = makeInvoice(
      total = 1000,
      finalizedAt = Some(now),
      paidAt = Some(now.plusSeconds(3600)),
    )

    val metronomeLineItem = makeMetronomeLineItem(
      name = Some("API usage"),
      total = Some(BigDecimal(1000)),
    )

    // The breakdown spans 2 accounting periods: 400 is consumed before the invoice is finalized, so it's
    // recognized as unbilled revenue in August and reclassified to AR when the invoice is finalized in September.
    val metronomeInvoice = makeRichMetronomeInvoice(
      base = makeMetronomeInvoice(billingProviderInvoiceId = Some(invoice.id)),
      lineItems = Seq(metronomeLineItem),
      breakdownInvoice = Some(makeRichMetronomeBreakdownInvoice(
        lineItems = Seq(
          makeMetronomeBreakdownLineItem(
            lineItemId = Some(metronomeLineItem.id),
            total = Some(BigDecimal(400)),
            breakdownStartTimestamp = Some(Instant.parse("2025-08-01T00:00:00Z")),
            breakdownEndTimestamp = Some(Instant.parse("2025-09-01T00:00:00Z")),
          ),
          makeMetronomeBreakdownLineItem(
            lineItemId = Some(metronomeLineItem.id),
            total = Some(BigDecimal(600)),
            breakdownStartTimestamp = Some(Instant.parse("2025-09-01T00:00:00Z")),
            breakdownEndTimestamp = Some(Instant.parse("2025-10-01T00:00:00Z")),
          ),
        )
      ))
    )

    val transaction = makeProcessInvoice(
      transaction = makeTransaction(id = invoice.id, tpe = Transaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          description = Some("API usage"),
          amount = 1000,
          startedAt = Some(now),
          endedAt = Some(now),
        ))),
        payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
          amount = 1000,
          balanceTransactionAmount = 1000,
          balanceTransactionFeeAmount = 10,
          createdAt = now.plusSeconds(3600)
        ))))
      ),
      metronomeInvoice = Some(metronomeInvoice)
    )

    val entries = transaction.generateRawJournalEntries()

    NetAmount.compute(entries, endPeriod = Some(Instant.parse("2025-08-01T00:00:00Z"))) should be(Seq(
      NetAmount(400, Revenue),
      NetAmount(400, UnbilledAccountsReceivable)
    ))

    NetAmount.compute(entries) should be(Seq(
      NetAmount(990, Cash),
      NetAmount(10, Fees),
      NetAmount(1000, Revenue)
    ))
  }

  it("The Metronome amount is less than the Stripe amount") {
    val now = Instant.parse("2025-09-02T00:00:00Z")
    val invoice = makeInvoice(
      total = 1000,
      finalizedAt = Some(now),
      paidAt = Some(now.plusSeconds(3600)),
    )

    val metronomeLineItem = makeMetronomeLineItem(
      name = Some("API usage"),
      total = Some(BigDecimal(1000)),
    )

    val metronomeInvoice = makeRichMetronomeInvoice(
      base = makeMetronomeInvoice(billingProviderInvoiceId = Some(invoice.id)),
      lineItems = Seq(metronomeLineItem),
      breakdownInvoice = Some(makeRichMetronomeBreakdownInvoice(
        lineItems = Seq(
          makeMetronomeBreakdownLineItem(
            lineItemId = Some(metronomeLineItem.id),
            total = Some(BigDecimal(100)),
            breakdownStartTimestamp = Some(Instant.parse("2025-08-01T00:00:00Z")),
            breakdownEndTimestamp = Some(Instant.parse("2025-09-01T00:00:00Z")),
          ),
          makeMetronomeBreakdownLineItem(
            lineItemId = Some(metronomeLineItem.id),
            total = Some(BigDecimal(200)),
            breakdownStartTimestamp = Some(Instant.parse("2025-09-01T00:00:00Z")),
            breakdownEndTimestamp = Some(Instant.parse("2025-10-01T00:00:00Z")),
          ),
        )
      ))
    )

    val transaction = makeProcessInvoice(
      transaction = makeTransaction(id = invoice.id, tpe = Transaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          description = Some("API usage"),
          amount = 1000,
          startedAt = Some(now),
          endedAt = Some(now),
        ))),
        payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
          amount = 1000,
          balanceTransactionAmount = 1000,
          balanceTransactionFeeAmount = 10,
          createdAt = now.plusSeconds(3600)
        ))))
      ),
      metronomeInvoice = Some(metronomeInvoice)
    )

    val entries = transaction.generateRawJournalEntries()

    NetAmount.compute(entries, endPeriod = Some(Instant.parse("2025-08-01T00:00:00Z"))) should be(Seq(
      NetAmount(334, Revenue),
      NetAmount(334, UnbilledAccountsReceivable)
    ))
    NetAmount.compute(entries, endPeriod = Some(Instant.parse("2025-08-01T00:00:00Z")), usePresentmentAmount = true) should be(Seq(
      NetAmount(334, Revenue),
      NetAmount(334, UnbilledAccountsReceivable)
    ))

    NetAmount.compute(entries) should be(Seq(
      NetAmount(990, Cash),
      NetAmount(10, Fees),
      NetAmount(1000, Revenue)
    ))
    NetAmount.compute(entries, usePresentmentAmount = true) should be(Seq(
      NetAmount(990, Cash),
      NetAmount(10, Fees),
      NetAmount(1000, Revenue)
    ))
  }

  it("books prepaid commit, credit applied, and no breakdown line items correctly") {
    val now = Instant.parse("2025-09-02T00:00:00Z")
    val invoice = makeInvoice(
      total = 400,
      finalizedAt = Some(now),
      paidAt = Some(now.plusSeconds(3600)),
    )

    val metronomeLineItems = Seq(
      makeMetronomeLineItem(
        name = Some("Credit purchase"),
        total = Some(BigDecimal(300)),
        metadata = Some(Json.obj("commit_type" -> "PrepaidCommit").toString)
      ),
      makeMetronomeLineItem(
        name = Some("Credit applied"),
        total = Some(BigDecimal(-100)),
      ),
      makeMetronomeLineItem(
        name = Some("Platform fee"),
        total = Some(BigDecimal(200)),
      ),
    )

    val metronomeInvoice = makeRichMetronomeInvoice(
      base = makeMetronomeInvoice(billingProviderInvoiceId = Some(invoice.id)),
      lineItems = metronomeLineItems,
      breakdownInvoice = Some(makeRichMetronomeBreakdownInvoice(
        lineItems = Seq(
          makeMetronomeBreakdownLineItem(
            lineItemId = Some(metronomeLineItems(1).id),
            total = Some(BigDecimal(-60)),
            breakdownStartTimestamp = Some(Instant.parse("2025-08-01T00:00:00Z")),
            breakdownEndTimestamp = Some(Instant.parse("2025-09-01T00:00:00Z")),
            lineItemType = Some("applied_commit_or_credit")
          ),
          makeMetronomeBreakdownLineItem(
            lineItemId = Some(metronomeLineItems(1).id),
            total = Some(BigDecimal(-40)),
            breakdownStartTimestamp = Some(Instant.parse("2025-09-01T00:00:00Z")),
            breakdownEndTimestamp = Some(Instant.parse("2025-10-01T00:00:00Z")),
            lineItemType = Some("applied_commit_or_credit")
          ),
        )
      ))
    )

    val transaction = makeProcessInvoice(
      transaction = makeTransaction(id = invoice.id, tpe = Transaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(
          makeRichInvoiceLineItem(
            base = makeInvoiceLineItem(
              invoiceId = invoice.id,
              description = Some("Credit purchase"),
              amount = 300,
            )
          ),
          makeRichInvoiceLineItem(
            base = makeInvoiceLineItem(
              invoiceId = invoice.id,
              description = Some("Credit applied"),
              amount = -100,
            )
          ),
          makeRichInvoiceLineItem(
            base = makeInvoiceLineItem(
              invoiceId = invoice.id,
              description = Some("Platform fee"),
              amount = 200,
              startedAt = Some(Instant.parse("2025-08-15T00:00:00Z")),
              endedAt = Some(Instant.parse("2025-09-05T00:00:00Z")),
            )
          )
        ),
        payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
          amount = invoice.total,
          balanceTransactionAmount = invoice.total,
          balanceTransactionFeeAmount = 10,
          createdAt = now.plusSeconds(3600)
        ))))
      ),
      metronomeInvoice = Some(metronomeInvoice)
    )

    val entries = transaction.generateRawJournalEntries()

    NetAmount.compute(entries, endPeriod = Some(Instant.parse("2025-08-01T00:00:00Z"))) should be(Seq(
      NetAmount(162, Revenue),
      NetAmount(200, UnbilledAccountsReceivable),
      NetAmount(38, UnbilledDeferredRevenue),
    ))

    NetAmount.compute(entries) should be(Seq(
      NetAmount(390, Cash),
      NetAmount(10, Fees),
      NetAmount(200, MetronomeCreditBalance),
      NetAmount(200, Revenue),
    ))
  }
}

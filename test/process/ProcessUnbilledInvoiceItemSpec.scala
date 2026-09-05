package process

import base.Base
import database.models.*
import database.models.JournalEntry.Account.*
import framework.{Instant, NetAmount}
import services.ExchangeRate

import java.time.temporal.ChronoUnit

class ProcessUnbilledInvoiceItemSpec extends Base {
  it("books unbilled invoice items") {
    val now = Instant.now()

    val transaction = makeProcessUnbilledInvoiceItem(
      transaction = makeRevRecTransaction(tpe = RevRecTransaction.Type.UnbilledInvoiceItem),
      invoiceItem = makeRichInvoiceItem(base = makeInvoiceItem(
        amount = 1200,
        currency = "usd",
        createdAt = now,
        startedAt = Some(now),
        endedAt = Some(now.plus(360, ChronoUnit.DAYS)),
      ))
    )

    val entries = transaction.generateRawJournalEntries()

    NetAmount.compute(entries, endPeriod = Some(now.plus(90, ChronoUnit.DAYS))) should be(Seq(
      NetAmount(336, Revenue),
      NetAmount(1200, UnbilledAccountsReceivable),
      NetAmount(864, UnbilledDeferredRevenue),
    ))

    NetAmount.compute(entries) should be(Seq(
      NetAmount(1200, Revenue),
      NetAmount(1200, UnbilledAccountsReceivable),
    ))
  }

  it("books unbilled invoice items settled in a different currency") {
    val now = Instant.now()

    val transaction = makeProcessUnbilledInvoiceItem(
      transaction = makeRevRecTransaction(tpe = RevRecTransaction.Type.UnbilledInvoiceItem),
      invoiceItem = makeRichInvoiceItem(
        base = makeInvoiceItem(
          amount = 1000,
          currency = "usd",
          createdAt = now,
          startedAt = Some(now),
          endedAt = Some(now.plus(360, ChronoUnit.DAYS)),
        ),
        createdAtExchangeRate = Some(ExchangeRate("usd", "eur", 100, 90))
      )
    )

    val entries = transaction.generateRawJournalEntries()
    val amounts = NetAmount.compute(entries)

    NetAmount.compute(entries, endPeriod = Some(now.plus(90, ChronoUnit.DAYS))) should be(Seq(
      NetAmount(252, Revenue, "eur"),
      NetAmount(900, UnbilledAccountsReceivable, "eur"),
      NetAmount(648, UnbilledDeferredRevenue, "eur"),
    ))

    amounts should be(Seq(
      NetAmount(900, Revenue, "eur"),
      NetAmount(900, UnbilledAccountsReceivable, "eur"),
    ))
  }
}

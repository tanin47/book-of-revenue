package process

import base.Base
import framework.NetAmount
import database.models.*
import database.models.JournalEntry.Account.*
import framework.Instant
import process.Helpers.getAccountingPeriod

import java.time.temporal.ChronoUnit

class ProcessInvoicePrepaidCreditNoteWithoutLineItemSpec extends Base {
  it("issues a credit note and voids in a later period") {
    val finalizedAt = Instant.parse("2026-01-01T00:00:00Z")
    val issuedAt = Instant.parse("2026-02-15T00:00:00Z")
    val voidedAt = Instant.parse("2026-04-15T00:00:00Z")

    val invoice = makeInvoice(
      total = 1400,
      finalizedAt = Some(finalizedAt),
    )

    val lineItem = makeRichInvoiceLineItem(
      base = makeInvoiceLineItem(
        invoiceId = invoice.id,
        amount = 1250,
        startedAt = Some(finalizedAt),
        endedAt = Some(finalizedAt.plus(1200, java.time.temporal.ChronoUnit.DAYS)),
      ),
      taxes = Seq(makeInvoiceLineItemTax(amount = 200, taxBehaviour = "exclusive")),
      pretaxCreditAmounts = Seq(
        makeDiscountPretaxCreditAmount(rank = 0, amount = 50),
      ),
    )

    val creditNote = makeRichCreditNote(
      base = makeCreditNote(
        `type` = "pre_payment",
        invoiceId = invoice.id,
        total = 400,
        prePaymentAmount = 400,
        effectiveAt = Some(issuedAt),
        voidedAt = Some(voidedAt),
      ),
      lines = Seq.empty
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(lineItem),
        creditNotes = Seq(creditNote),
      ),
    )

    val entries = transaction.generateRawJournalEntries()

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(issuedAt).minus(1, ChronoUnit.DAYS))) should be(Seq(
      NetAmount(1400, AccountsReceivable),
      NetAmount(1169, DeferredRevenue),
      NetAmount(31, Revenue),
      NetAmount(200, TaxLiability),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(issuedAt))) should be(Seq(
      NetAmount(1000, AccountsReceivable),
      NetAmount(9, CreditNotes),
      NetAmount(816, DeferredRevenue),
      NetAmount(50, Revenue),
      NetAmount(143, TaxLiability),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(voidedAt))) should be(Seq(
      NetAmount(1400, AccountsReceivable),
      NetAmount(1080, DeferredRevenue),
      NetAmount(120, Revenue),
      NetAmount(200, TaxLiability),
    ))

    NetAmount.compute(entries) should be(Seq(
      NetAmount(1400, AccountsReceivable),
      NetAmount(1200, Revenue),
      NetAmount(200, TaxLiability),
    ))
  }

  it("issues a credit note and voids in the same period") {
    val finalizedAt = Instant.parse("2026-01-01T00:00:00Z")
    val issuedAt = Instant.parse("2026-02-15T00:00:00Z")
    val voidedAt = Instant.parse("2026-02-16T00:00:00Z")

    val invoice = makeInvoice(
      total = 1400,
      finalizedAt = Some(finalizedAt),
    )

    val lineItem = makeRichInvoiceLineItem(
      base = makeInvoiceLineItem(
        invoiceId = invoice.id,
        amount = 1250,
        startedAt = Some(finalizedAt),
        endedAt = Some(finalizedAt.plus(1200, java.time.temporal.ChronoUnit.DAYS)),
      ),
      taxes = Seq(makeInvoiceLineItemTax(amount = 200, taxBehaviour = "exclusive")),
      pretaxCreditAmounts = Seq(
        makeDiscountPretaxCreditAmount(rank = 0, amount = 50),
      ),
    )

    val creditNote = makeRichCreditNote(
      base = makeCreditNote(
        `type` = "pre_payment",
        invoiceId = invoice.id,
        total = 400,
        prePaymentAmount = 400,
        effectiveAt = Some(issuedAt),
        voidedAt = Some(voidedAt),
      ),
      lines = Seq.empty
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(lineItem),
        creditNotes = Seq(creditNote),
      ),
    )

    val entries = transaction.generateRawJournalEntries()

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(issuedAt).minus(1, ChronoUnit.DAYS))) should be(Seq(
      NetAmount(1400, AccountsReceivable),
      NetAmount(1169, DeferredRevenue),
      NetAmount(31, Revenue),
      NetAmount(200, TaxLiability),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(issuedAt))) should be(Seq(
      NetAmount(1400, AccountsReceivable),
      NetAmount(1141, DeferredRevenue),
      NetAmount(59, Revenue),
      NetAmount(200, TaxLiability),
    ))

    NetAmount.compute(entries) should be(Seq(
      NetAmount(1400, AccountsReceivable),
      NetAmount(1200, Revenue),
      NetAmount(200, TaxLiability),
    ))
  }
}

package process

import base.Base
import framework.NetAmount
import database.models.*
import database.models.JournalEntry.Account.*
import framework.Instant
import process.Helpers.getAccountingPeriod

import java.time.temporal.ChronoUnit

class ProcessInvoicePrepaidCreditNoteWithLineItemSpec extends Base {
  it("issues a credit note and voids in a later period, and the invoice is later voided") {
    val finalizedAt = Instant.parse("2026-01-01T00:00:00Z")
    val creditNoteIssuedAt = Instant.parse("2026-02-15T00:00:00Z")
    val creditNoteVoidedAt = Instant.parse("2026-04-15T00:00:00Z")
    val voidedAt = Instant.parse("2026-05-15T00:00:00Z")

    val invoice = makeInvoice(
      total = 1400,
      finalizedAt = Some(finalizedAt),
      voidedAt = Some(voidedAt),
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
        total = 380,
        prePaymentAmount = 380,
        effectiveAt = Some(creditNoteIssuedAt),
        voidedAt = Some(creditNoteVoidedAt),
      ),
      lines = Seq(makeRichCreditNoteLineItem(
        base = makeCreditNoteLineItem(
          amount = 400,
          invoiceLineItemId = Some(lineItem.base.id),
        ),
        taxes = Seq(makeCreditNoteLineItemTax(amount = 190, taxBehavior = "inclusive")),
        pretaxCreditAmounts = Seq(
          makeCreditNoteDiscountPretaxCreditAmount(rank = 0, amount = 20),
        ),
      )),
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

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(creditNoteIssuedAt).minus(1, ChronoUnit.DAYS))) should be(Seq(
      NetAmount(1400, AccountsReceivable),
      NetAmount(1169, DeferredRevenue),
      NetAmount(31, Revenue),
      NetAmount(200, TaxLiability),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(creditNoteIssuedAt))) should be(Seq(
      NetAmount(1020, AccountsReceivable),
      NetAmount(5, CreditNotes),
      NetAmount(961, DeferredRevenue),
      NetAmount(54, Revenue),
      NetAmount(10, TaxLiability),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(creditNoteVoidedAt))) should be(Seq(
      NetAmount(1400, AccountsReceivable),
      NetAmount(1080, DeferredRevenue),
      NetAmount(120, Revenue),
      NetAmount(200, TaxLiability),
    ))

    NetAmount.compute(entries) should be(Seq(
      NetAmount(120, Revenue),
      NetAmount(120, Voids),
    ))
  }

  it("issues a credit note and voids in a later period, and the invoice is voided when the credit note is voided") {
    val finalizedAt = Instant.parse("2026-01-01T00:00:00Z")
    val creditNoteIssuedAt = Instant.parse("2026-02-15T00:00:00Z")
    val creditNoteVoidedAt = Instant.parse("2026-04-15T00:00:00Z")
    val voidedAt = Instant.parse("2026-04-16T00:00:00Z")

    val invoice = makeInvoice(
      total = 1400,
      finalizedAt = Some(finalizedAt),
      voidedAt = Some(voidedAt),
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
        total = 380,
        prePaymentAmount = 380,
        effectiveAt = Some(creditNoteIssuedAt),
        voidedAt = Some(creditNoteVoidedAt),
      ),
      lines = Seq(makeRichCreditNoteLineItem(
        base = makeCreditNoteLineItem(
          amount = 400,
          invoiceLineItemId = Some(lineItem.base.id),
        ),
        taxes = Seq(makeCreditNoteLineItemTax(amount = 190, taxBehavior = "inclusive")),
        pretaxCreditAmounts = Seq(
          makeCreditNoteDiscountPretaxCreditAmount(rank = 0, amount = 20),
        ),
      )),
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

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(creditNoteIssuedAt).minus(1, ChronoUnit.DAYS))) should be(Seq(
      NetAmount(1400, AccountsReceivable),
      NetAmount(1169, DeferredRevenue),
      NetAmount(31, Revenue),
      NetAmount(200, TaxLiability),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(creditNoteIssuedAt))) should be(Seq(
      NetAmount(1020, AccountsReceivable),
      NetAmount(5, CreditNotes),
      NetAmount(961, DeferredRevenue),
      NetAmount(54, Revenue),
      NetAmount(10, TaxLiability),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(creditNoteVoidedAt))) should be(Seq(
      NetAmount(75, Revenue),
      NetAmount(75, Voids),
    ))
  }

  it("issues a credit note and the invoice is voided") {
    val finalizedAt = Instant.parse("2026-01-01T00:00:00Z")
    val creditNoteIssuedAt = Instant.parse("2026-02-15T00:00:00Z")
    val voidedAt = Instant.parse("2026-04-16T00:00:00Z")

    val invoice = makeInvoice(
      total = 1400,
      finalizedAt = Some(finalizedAt),
      voidedAt = Some(voidedAt),
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
        total = 380,
        prePaymentAmount = 380,
        effectiveAt = Some(creditNoteIssuedAt),
      ),
      lines = Seq(makeRichCreditNoteLineItem(
        base = makeCreditNoteLineItem(
          amount = 400,
          invoiceLineItemId = Some(lineItem.base.id),
        ),
        taxes = Seq(makeCreditNoteLineItemTax(amount = 190, taxBehavior = "inclusive")),
        pretaxCreditAmounts = Seq(
          makeCreditNoteDiscountPretaxCreditAmount(rank = 0, amount = 20),
        ),
      )),
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

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(creditNoteIssuedAt).minus(1, ChronoUnit.DAYS))) should be(Seq(
      NetAmount(1400, AccountsReceivable),
      NetAmount(1169, DeferredRevenue),
      NetAmount(31, Revenue),
      NetAmount(200, TaxLiability),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(creditNoteIssuedAt))) should be(Seq(
      NetAmount(1020, AccountsReceivable),
      NetAmount(5, CreditNotes),
      NetAmount(961, DeferredRevenue),
      NetAmount(54, Revenue),
      NetAmount(10, TaxLiability),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(voidedAt))) should be(Seq(
      NetAmount(80, Revenue),
      NetAmount(80, Voids),
    ))
  }

  it("issues a credit note and voids in a later period, and the invoice is marked uncollectible when the credit note is voided") {
    val finalizedAt = Instant.parse("2026-01-01T00:00:00Z")
    val creditNoteIssuedAt = Instant.parse("2026-02-15T00:00:00Z")
    val creditNoteVoidedAt = Instant.parse("2026-04-15T00:00:00Z")
    val uncollectibleAt = Instant.parse("2026-04-16T00:00:00Z")

    val invoice = makeInvoice(
      total = 1400,
      finalizedAt = Some(finalizedAt),
      markedUncollectibleAt = Some(uncollectibleAt),
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
        total = 380,
        prePaymentAmount = 380,
        effectiveAt = Some(creditNoteIssuedAt),
        voidedAt = Some(creditNoteVoidedAt),
      ),
      lines = Seq(makeRichCreditNoteLineItem(
        base = makeCreditNoteLineItem(
          amount = 400,
          invoiceLineItemId = Some(lineItem.base.id),
        ),
        taxes = Seq(makeCreditNoteLineItemTax(amount = 190, taxBehavior = "inclusive")),
        pretaxCreditAmounts = Seq(
          makeCreditNoteDiscountPretaxCreditAmount(rank = 0, amount = 20),
        ),
      )),
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

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(creditNoteIssuedAt).minus(1, ChronoUnit.DAYS))) should be(Seq(
      NetAmount(1400, AccountsReceivable),
      NetAmount(1169, DeferredRevenue),
      NetAmount(31, Revenue),
      NetAmount(200, TaxLiability),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(creditNoteIssuedAt))) should be(Seq(
      NetAmount(1020, AccountsReceivable),
      NetAmount(5, CreditNotes),
      NetAmount(961, DeferredRevenue),
      NetAmount(54, Revenue),
      NetAmount(10, TaxLiability),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(creditNoteVoidedAt))) should be(Seq(
      NetAmount(75, BadDebt),
      NetAmount(75, Revenue),
    ))
  }

  it("issues a credit note and then the invoice is marked as uncollectible") {
    val finalizedAt = Instant.parse("2026-01-01T00:00:00Z")
    val creditNoteIssuedAt = Instant.parse("2026-02-15T00:00:00Z")
    val uncollectibleAt = Instant.parse("2026-04-16T00:00:00Z")

    val invoice = makeInvoice(
      total = 1400,
      finalizedAt = Some(finalizedAt),
      markedUncollectibleAt = Some(uncollectibleAt),
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
        total = 380,
        prePaymentAmount = 380,
        effectiveAt = Some(creditNoteIssuedAt),
      ),
      lines = Seq(makeRichCreditNoteLineItem(
        base = makeCreditNoteLineItem(
          amount = 400,
          invoiceLineItemId = Some(lineItem.base.id),
        ),
        taxes = Seq(makeCreditNoteLineItemTax(amount = 190, taxBehavior = "inclusive")),
        pretaxCreditAmounts = Seq(
          makeCreditNoteDiscountPretaxCreditAmount(rank = 0, amount = 20),
        ),
      )),
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

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(creditNoteIssuedAt).minus(1, ChronoUnit.DAYS))) should be(Seq(
      NetAmount(1400, AccountsReceivable),
      NetAmount(1169, DeferredRevenue),
      NetAmount(31, Revenue),
      NetAmount(200, TaxLiability),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(creditNoteIssuedAt))) should be(Seq(
      NetAmount(1020, AccountsReceivable),
      NetAmount(5, CreditNotes),
      NetAmount(961, DeferredRevenue),
      NetAmount(54, Revenue),
      NetAmount(10, TaxLiability),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(uncollectibleAt))) should be(Seq(
      NetAmount(75, BadDebt),
      NetAmount(5, CreditNotes),
      NetAmount(80, Revenue),
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
        total = 380,
        prePaymentAmount = 380,
        effectiveAt = Some(issuedAt),
        voidedAt = Some(voidedAt),
      ),
      lines = Seq(makeRichCreditNoteLineItem(
        base = makeCreditNoteLineItem(
          amount = 400,
          invoiceLineItemId = Some(lineItem.base.id),
        ),
        taxes = Seq(makeCreditNoteLineItemTax(amount = 190, taxBehavior = "inclusive")),
        pretaxCreditAmounts = Seq(
          makeCreditNoteDiscountPretaxCreditAmount(rank = 0, amount = 20),
        ),
      )),
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

  it("issues a credit note against a usage-based line item with credit grants, discounts, and taxes, then voids later") {
    val finalizedAt = Instant.parse("2026-02-15T00:00:00Z")
    val issuedAt = Instant.parse("2026-03-15T00:00:00Z")
    val voidedAt = Instant.parse("2026-05-15T00:00:00Z")

    val invoice = makeInvoice(
      total = 770,
      currency = "usd",
      finalizedAt = Some(finalizedAt),
    )

    val price = makeRichPrice(base = makePrice(billingScheme = "per_unit", unitAmount = 5, currency = "usd", recurringUsageType = Some("metered")))

    val lineItem = makeRichInvoiceLineItem(
      base = makeInvoiceLineItem(
        invoiceId = invoice.id,
        amount = 770,
        currency = "usd",
        startedAt = Some(Instant.parse("2026-01-14T00:00:00Z")),
        endedAt = Some(Instant.parse("2026-02-20T00:00:00Z")),
        pricingUnitAmountDecimal = Some("5"),
      ),
      price = Some(price),
      subscriptionItem = Some(makeSubscriptionItem()),
      meterEventSummaries = Seq(
        makeMeterEventSummary(aggregatedValue = 100, startTime = Instant.parse("2026-01-16T00:00:00Z"), endTime = Instant.parse("2026-01-17T00:00:00Z")),
        makeMeterEventSummary(aggregatedValue = 50, startTime = Instant.parse("2026-02-16T00:00:00Z"), endTime = Instant.parse("2026-02-17T00:00:00Z")),
      ),
      taxes = Seq(makeInvoiceLineItemTax(amount = 20, taxBehaviour = "inclusive")),
      pretaxCreditAmounts = Seq(
        makeRichInvoiceLineItemPretaxCreditAmount(
          base = makeInvoiceLineItemPretaxCreditAmount(rank = 0, amount = 100),
          creditBalanceTransaction = Some(makeRichCreditBalanceTransaction(creditGrant = Some(makeCreditGrant(category = "paid"))))
        ),
        makeRichInvoiceLineItemPretaxCreditAmount(
          base = makeInvoiceLineItemPretaxCreditAmount(rank = 1, amount = 50),
          creditBalanceTransaction = Some(makeRichCreditBalanceTransaction(creditGrant = Some(makeCreditGrant(category = "promotional"))))
        ),
        makeDiscountPretaxCreditAmount(rank = 2, amount = 50),
      ),
    )

    val creditNote = makeRichCreditNote(
      base = makeCreditNote(
        `type` = "pre_payment",
        invoiceId = invoice.id,
        total = 180,
        prePaymentAmount = 180,
        effectiveAt = Some(issuedAt),
        voidedAt = Some(voidedAt),
      ),
      lines = Seq(makeRichCreditNoteLineItem(
        base = makeCreditNoteLineItem(
          amount = 200,
          invoiceLineItemId = Some(lineItem.base.id),
        ),
        taxes = Seq(makeCreditNoteLineItemTax(amount = 13, taxBehavior = "inclusive")),
        pretaxCreditAmounts = Seq(
          makeCreditNoteDiscountPretaxCreditAmount(rank = 0, amount = 20),
        ),
      )),
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
      NetAmount(570, AccountsReceivable),
      NetAmount(-100, PaidCreditGrants),
      NetAmount(-50, PromotionalCreditGrants),
      NetAmount(700, Revenue),
      NetAmount(20, TaxLiability),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(issuedAt))) should be(Seq(
      NetAmount(390, AccountsReceivable),
      NetAmount(167, CreditNotes),
      NetAmount(-100, PaidCreditGrants),
      NetAmount(-50, PromotionalCreditGrants),
      NetAmount(700, Revenue),
      NetAmount(7, TaxLiability),
    ))

    NetAmount.compute(entries) should be(Seq(
      NetAmount(570, AccountsReceivable),
      NetAmount(-100, PaidCreditGrants),
      NetAmount(-50, PromotionalCreditGrants),
      NetAmount(700, Revenue),
      NetAmount(20, TaxLiability),
    ))
  }
}

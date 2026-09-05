package process

import base.Base
import framework.NetAmount
import database.models.*
import database.models.JournalEntry.Account.*
import framework.Instant
import process.Helpers.getAccountingPeriod

import java.time.temporal.ChronoUnit

class ProcessInvoicePostpaidCreditNoteWithoutLineItemSpec extends Base {
  it("issues a post-paid credit note that is tied to customer balance with tax and discount") {
    val finalizedAt = Instant.parse("2026-01-01T00:00:00Z")
    val paidAt = finalizedAt.plusSeconds(3600)
    val issuedAt = Instant.parse("2026-02-15T00:00:00Z")

    val invoice = makeInvoice(
      total = 1700,
      finalizedAt = Some(finalizedAt),
      paidAt = Some(paidAt),
    )

    val lineItem = makeRichInvoiceLineItem(
      base = makeInvoiceLineItem(
        invoiceId = invoice.id,
        amount = 1050,
        startedAt = Some(finalizedAt),
        endedAt = Some(finalizedAt.plus(1000, java.time.temporal.ChronoUnit.DAYS)),
      ),
      taxes = Seq(makeInvoiceLineItemTax(amount = 200, taxBehaviour = "exclusive")),
      pretaxCreditAmounts = Seq(
        makeDiscountPretaxCreditAmount(rank = 0, amount = 50),
      ),
    )

    val otherLineItem = makeRichInvoiceLineItem(base = makeInvoiceLineItem(
      invoiceId = invoice.id,
      amount = 500,
      rank = 1,
      startedAt = Some(finalizedAt),
      endedAt = Some(Instant.parse("2026-02-01T00:00:00Z")),
    ))

    val creditNote = makeRichCreditNote(
      base = makeCreditNote(
        `type` = "post_payment",
        invoiceId = invoice.id,
        total = 950,
        effectiveAt = Some(issuedAt),
      ),
      customerBalanceTransaction = Some(makeCustomerBalanceTransaction(
        amount = -950,
        `type` = "credit_note",
        created = issuedAt,
      )),
      lines = Seq.empty
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(lineItem, otherLineItem),
        payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
          amount = 1700,
          balanceTransactionAmount = 1700,
          createdAt = paidAt,
        )))),
        creditNotes = Seq(creditNote),
      ),
    )

    val entries = transaction.generateRawJournalEntries()

    NetAmount.compute(entries, lineItemId = Some(otherLineItem.base.id)) should be(Seq(
      NetAmount(500, Cash),
      NetAmount(279, CreditNotes),
      NetAmount(279, CustomerBalance),
      NetAmount(500, Revenue),
    ))

    NetAmount.compute(entries, lineItemId = Some(lineItem.base.id)) should be(Seq(
      NetAmount(1200, Cash),
      NetAmount(671, CustomerBalance),
      NetAmount(440, Revenue),
      NetAmount(89, TaxLiability),
    ))

    NetAmount.compute(entries) should be(Seq(
      NetAmount(1700, Cash),
      NetAmount(279, CreditNotes),
      NetAmount(950, CustomerBalance),
      NetAmount(940, Revenue),
      NetAmount(89, TaxLiability),
    ))
  }

  it("issues a post-paid credit note that is refunded out of band") {
    val finalizedAt = Instant.parse("2026-02-15T00:00:00Z")
    val paidAt = Instant.parse("2026-03-15T00:00:00Z")
    val issuedAt = Instant.parse("2026-04-15T00:00:00Z")

    val invoice = makeInvoice(
      total = 570,
      currency = "usd",
      finalizedAt = Some(finalizedAt),
      paidAt = Some(paidAt),
    )

    val price = makeRichPrice(base = makePrice(billingScheme = "per_unit", unitAmount = 5, currency = "usd", recurringUsageType = Some("metered")))

    val lineItem = makeRichInvoiceLineItem(
      base = makeInvoiceLineItem(
        invoiceId = invoice.id,
        amount = 750,
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
      taxes = Seq(makeInvoiceLineItemTax(amount = 20, taxBehaviour = "exclusive")),
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
        `type` = "post_payment",
        invoiceId = invoice.id,
        total = 318,
        outOfBandAmount = Some(318),
        effectiveAt = Some(issuedAt),
      ),
      lines = Seq.empty
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(lineItem),
        payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
          amount = 570,
          balanceTransactionAmount = 570,
          createdAt = paidAt,
        )))),
        creditNotes = Seq(creditNote),
      ),
    )

    val entries = transaction.generateRawJournalEntries()

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(paidAt).minus(1, ChronoUnit.DAYS))) should be(Seq(
      NetAmount(570, AccountsReceivable),
      NetAmount(-100, PaidCreditGrants),
      NetAmount(-50, PromotionalCreditGrants),
      NetAmount(700, Revenue),
      NetAmount(20, TaxLiability),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(paidAt))) should be(Seq(
      NetAmount(570, Cash),
      NetAmount(-100, PaidCreditGrants),
      NetAmount(-50, PromotionalCreditGrants),
      NetAmount(700, Revenue),
      NetAmount(20, TaxLiability),
    ))

    NetAmount.compute(entries) should be(Seq(
      NetAmount(570, Cash),
      NetAmount(307, CreditNotes),
      NetAmount(-318, OutOfBandAssets),
      NetAmount(-100, PaidCreditGrants),
      NetAmount(-50, PromotionalCreditGrants),
      NetAmount(700, Revenue),
      NetAmount(9, TaxLiability),
    ))
  }

  it("issues a post-paid credit note that is refunded but the refund is issued earlier") {
    val finalizedAt = Instant.parse("2026-01-01T00:00:00Z")
    val paidAt = finalizedAt.plusSeconds(3600)
    val refundedAt = Instant.parse("2026-02-15T00:00:00Z")
    val creditNoteIssuedAt = Instant.parse("2026-02-15T00:00:00Z")

    val invoice = makeInvoice(
      total = 1500,
      finalizedAt = Some(finalizedAt),
      paidAt = Some(paidAt),
    )

    val lineItem = makeRichInvoiceLineItem(base = makeInvoiceLineItem(
      invoiceId = invoice.id,
      amount = 1000,
      startedAt = Some(finalizedAt),
      endedAt = Some(finalizedAt.plus(1000, java.time.temporal.ChronoUnit.DAYS)),
    ))

    val otherLineItem = makeRichInvoiceLineItem(base = makeInvoiceLineItem(
      invoiceId = invoice.id,
      amount = 500,
      rank = 1,
      startedAt = Some(finalizedAt),
      endedAt = Some(finalizedAt.plus(500, java.time.temporal.ChronoUnit.DAYS)),
    ))

    val refund = makeRichCreditNoteRefund(
      base = makeCreditNoteRefund(amountRefunded = 1500),
      refund = Some(makeRichRefund2(
        amount = 1500,
        balanceTransactionAmount = -1500,
        createdAt = refundedAt,
        belongsToCreditNote = true,
      )),
    )

    val creditNote = makeRichCreditNote(
      base = makeCreditNote(
        `type` = "post_payment",
        invoiceId = invoice.id,
        total = 900,
        effectiveAt = Some(creditNoteIssuedAt),
      ),
      lines = Seq.empty,
      refunds = Seq(refund),
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(lineItem, otherLineItem),
        payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
          amount = 1500,
          balanceTransactionAmount = 1500,
          createdAt = paidAt,
          refunds = Seq(refund.refund.get) // This refund is ignored because it's handled by credit note.
        )))),
        creditNotes = Seq(creditNote),
      ),
    )

    val entries = transaction.generateRawJournalEntries()

    NetAmount.compute(entries, endPeriod = Some(paidAt)) should be(Seq(
      NetAmount(1500, Cash),
      NetAmount(1438, DeferredRevenue),
      NetAmount(62, Revenue),
    ))
    NetAmount.compute(entries, endPeriod = Some(refundedAt)) should be(Seq(
      NetAmount(62, Refunds),
      NetAmount(62, Revenue),
    ))
    NetAmount.compute(entries, endPeriod = Some(refundedAt), creditNoteId = Some(creditNote.base.id)) should be(Seq(
      NetAmount(-1500, Cash),
      NetAmount(-1382, DeferredRevenue),
      NetAmount(62, Refunds),
      NetAmount(-56, Revenue)
    ))
  }

  it("issues a post-paid credit note that is refunded") {
    val finalizedAt = Instant.parse("2026-01-01T00:00:00Z")
    val paidAt = finalizedAt.plusSeconds(3600)
    val issuedAt = Instant.parse("2027-01-15T00:00:00Z")
    val failedAt = Instant.parse("2027-02-15T00:00:00Z")

    val invoice = makeInvoice(
      total = 1500,
      finalizedAt = Some(finalizedAt),
      paidAt = Some(paidAt),
    )

    val lineItem = makeRichInvoiceLineItem(base = makeInvoiceLineItem(
      invoiceId = invoice.id,
      amount = 1000,
      startedAt = Some(finalizedAt),
      endedAt = Some(finalizedAt.plus(1000, java.time.temporal.ChronoUnit.DAYS)),
    ))

    val otherLineItem = makeRichInvoiceLineItem(base = makeInvoiceLineItem(
      invoiceId = invoice.id,
      amount = 500,
      rank = 1,
      startedAt = Some(finalizedAt),
      endedAt = Some(finalizedAt.plus(500, java.time.temporal.ChronoUnit.DAYS)),
    ))

    val refund = makeRichCreditNoteRefund(
      base = makeCreditNoteRefund(amountRefunded = 1500),
      refund = Some(makeRichRefund2(
        amount = 1500,
        balanceTransactionAmount = -1500,
        createdAt = issuedAt,
        failureBalanceTransactionAmount = Some(1500),
        failureBalanceTransactionCreatedAt = Some(failedAt),
        belongsToCreditNote = true,
      )),
    )

    val creditNote = makeRichCreditNote(
      base = makeCreditNote(
        `type` = "post_payment",
        invoiceId = invoice.id,
        total = 900,
        effectiveAt = Some(issuedAt),
      ),
      lines = Seq.empty,
      refunds = Seq(refund),
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(lineItem, otherLineItem),
        payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
          amount = 1500,
          balanceTransactionAmount = 1500,
          createdAt = paidAt,
          refunds = Seq(refund.refund.get) // This refund is ignored because it's handled by credit note.
        )))),
        creditNotes = Seq(creditNote),
      ),
    )

    val entries = transaction.generateRawJournalEntries()

    NetAmount.compute(entries, endPeriod = Some(paidAt)) should be(Seq(
      NetAmount(1500, Cash),
      NetAmount(1438, DeferredRevenue),
      NetAmount(62, Revenue),
    ))

    NetAmount.compute(entries, lineItemId = Some(lineItem.base.id)) should be(Seq(
      NetAmount(1000, Cash),
      NetAmount(1000, Recoverables),
      NetAmount(365, Refunds),
      NetAmount(365, Revenue),
    ))

    NetAmount.compute(entries, lineItemId = Some(otherLineItem.base.id)) should be(Seq(
      NetAmount(500, Cash),
      NetAmount(500, Recoverables),
      NetAmount(365, Refunds),
      NetAmount(365, Revenue),
    ))

    NetAmount.compute(entries) should be(Seq(
      NetAmount(1500, Cash),
      NetAmount(1500, Recoverables),
      NetAmount(730, Refunds),
      NetAmount(730, Revenue),
    ))
  }

  it("issues a post-paid credit note that is refunded via a payment record") {
    val finalizedAt = Instant.parse("2026-01-01T00:00:00Z")
    val paidAt = finalizedAt.plusSeconds(3600)
    val issuedAt = Instant.parse("2026-02-15T00:00:00Z")

    val invoice = makeInvoice(
      total = 1000,
      finalizedAt = Some(finalizedAt),
      paidAt = Some(paidAt),
    )

    val lineItem = makeRichInvoiceLineItem(base = makeInvoiceLineItem(
      invoiceId = invoice.id,
      amount = 1000,
      startedAt = Some(finalizedAt),
      endedAt = Some(finalizedAt.plus(1000, java.time.temporal.ChronoUnit.DAYS)),
    ))

    val creditNote = makeRichCreditNote(
      base = makeCreditNote(
        `type` = "post_payment",
        invoiceId = invoice.id,
        total = 600,
        effectiveAt = Some(issuedAt),
      ),
      lines = Seq.empty,
      refunds = Seq(makeRichCreditNoteRefund(
        base = makeCreditNoteRefund(amountRefunded = 600, paymentRecordRefundId = Some("prr_1")),
      )),
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(lineItem),
        payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
          amount = 1000,
          balanceTransactionAmount = 1000,
          createdAt = paidAt,
        )))),
        creditNotes = Seq(creditNote),
      ),
    )

    val entries = transaction.generateRawJournalEntries()

    NetAmount.compute(entries, endPeriod = Some(paidAt)) should be(Seq(
      NetAmount(1000, Cash),
      NetAmount(969, DeferredRevenue),
      NetAmount(31, Revenue),
    ))

    NetAmount.compute(entries) should be(Seq(
      NetAmount(1000, Cash),
      NetAmount(-600, OutOfBandAssets),
      NetAmount(400, Revenue),
    ))
  }


  it("issues a mixed credit note") {
    val finalizedAt = Instant.parse("2026-01-01T00:00:00Z")
    val paidAt = finalizedAt.plusSeconds(3600)
    val issuedAt = Instant.parse("2026-02-01T00:00:00Z")

    val invoice = makeInvoice(
      total = 1000,
      finalizedAt = Some(finalizedAt),
    )

    val lineItem = makeRichInvoiceLineItem(base = makeInvoiceLineItem(
      invoiceId = invoice.id,
      amount = 1000,
      startedAt = Some(finalizedAt),
      endedAt = Some(finalizedAt.plus(1000, java.time.temporal.ChronoUnit.DAYS)),
    ))

    val refund = makeRichCreditNoteRefund(
      base = makeCreditNoteRefund(amountRefunded = 300),
      refund = Some(makeRichRefund2(
        amount = 300,
        balanceTransactionAmount = -300,
        createdAt = issuedAt,
        belongsToCreditNote = true,
      )),
    )

    val creditNote = makeRichCreditNote(
      base = makeCreditNote(
        `type` = "mixed",
        invoiceId = invoice.id,
        total = 500,
        prePaymentAmount = 200,
        effectiveAt = Some(issuedAt),
      ),
      lines = Seq.empty,
      refunds = Seq(refund),
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(lineItem),
        payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
          amount = 500,
          balanceTransactionAmount = 500,
          createdAt = paidAt,
          refunds = Seq(refund.refund.get) // This refund is ignored because it's handled by credit note.
        )))),
        creditNotes = Seq(creditNote),
      ),
    )

    val entries = transaction.generateRawJournalEntries()

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(issuedAt).minus(1, ChronoUnit.DAYS))) should be(Seq(
      NetAmount(500, AccountsReceivable),
      NetAmount(500, Cash),
      NetAmount(969, DeferredRevenue),
      NetAmount(31, Revenue),
    ))

    NetAmount.compute(entries) should be(Seq(
      NetAmount(600, AccountsReceivable),
      NetAmount(200, Cash),
      NetAmount(7, CreditNotes),
      NetAmount(807, Revenue),
    ))
  }


  it("issues a post-paid credit note that is refunded out of band and customer balance but the original invoice is forgiven") {
    val finalizedAt = Instant.parse("2026-02-15T00:00:00Z")
    val invoicePaidAt = Instant.parse("2026-03-15T00:00:00Z")
    val creditNoteIssuedAt = Instant.parse("2026-04-15T00:00:00Z")
    val chargeCreatedAt = Instant.parse("2026-05-15T00:00:00Z")

    val invoice = makeInvoice(
      total = 570,
      currency = "usd",
      finalizedAt = Some(finalizedAt),
      paidAt = Some(invoicePaidAt),
    )

    val lineItem = makeRichInvoiceLineItem(
      base = makeInvoiceLineItem(
        invoiceId = invoice.id,
        amount = 750,
        pricingUnitAmountDecimal = Some("5"),
      ),
    )

    val cbtxn = makeCustomerBalanceTransaction(amount = -100, created = creditNoteIssuedAt)
    val creditNote = makeRichCreditNote(
      base = makeCreditNote(
        `type` = "post_payment",
        invoiceId = invoice.id,
        total = 400,
        outOfBandAmount = Some(300),
        customerBalanceTransactionId = Some(cbtxn.id),
        effectiveAt = Some(creditNoteIssuedAt),
      ),
      customerBalanceTransaction = Some(cbtxn),
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(lineItem),
        creditNotes = Seq(creditNote),
        payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
          amount = 750,
          balanceTransactionAmount = 750,
          createdAt = chargeCreatedAt,
        )))),
      ),
    )

    val entries = transaction.generateRawJournalEntries()

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(invoicePaidAt).minus(1, ChronoUnit.DAYS))) should be(Seq(
      NetAmount(750, AccountsReceivable),
      NetAmount(750, Revenue),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(invoicePaidAt))) should be(Seq(
      NetAmount(750, Revenue),
      NetAmount(750, Underpayment),
    ))

    NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(creditNoteIssuedAt))) should be(Seq(
      NetAmount(400, CreditNotes),
      NetAmount(100, CustomerBalance),
      NetAmount(-300, OutOfBandAssets),
      NetAmount(750, Revenue),
      NetAmount(750, Underpayment),
    ))

    NetAmount.compute(entries) should be(Seq(
      NetAmount(750, Cash),
      NetAmount(400, CreditNotes),
      NetAmount(100, CustomerBalance),
      NetAmount(-300, OutOfBandAssets),
      NetAmount(750, Recoverables),
      NetAmount(750, Revenue),
      NetAmount(750, Underpayment),
    ))
  }
}

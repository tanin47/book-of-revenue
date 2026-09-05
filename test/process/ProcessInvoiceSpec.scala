package process

import base.Base
import database.models.*
import database.models.JournalEntry.Account.*
import framework.{Instant, NetAmount}
import process.Helpers.getAccountingPeriod
import services.ExchangeRate

import java.time.temporal.ChronoUnit

class ProcessInvoiceSpec extends Base {
  it("paid invoice") {
    val now = Instant.now()
    val invoice = makeInvoice(
      total = 1000,
      finalizedAt = Some(now),
      paidAt = Some(now.plusSeconds(3600)),
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
          invoiceId = invoice.id,
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
    )

    val amounts = NetAmount.compute(transaction.generateRawJournalEntries())

    amounts should be(Seq(
      NetAmount(990, Cash),
      NetAmount(10, Fees),
      NetAmount(1000, Revenue)
    ))
  }

  it("invoice with a discount") {
    val now = Instant.now()
    val invoice = makeInvoice(
      total = 1000,
      finalizedAt = Some(now),
      paidAt = Some(now.plusSeconds(3600)),
    )
    val lineItems = Seq(
      makeRichInvoiceLineItem(
        base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          amount = 100,
          startedAt = Some(now),
          endedAt = Some(now),
        ),
        pretaxCreditAmounts = Seq(makeDiscountPretaxCreditAmount(amount = 50))
      ),
      makeRichInvoiceLineItem(
        base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          amount = 1200,
          startedAt = Some(now),
          endedAt = Some(now),
        ),
        pretaxCreditAmounts = Seq(makeDiscountPretaxCreditAmount(amount = 250))
      )
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = lineItems,
        payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
          amount = 1000,
          balanceTransactionAmount = 1000,
          balanceTransactionFeeAmount = 10,
          createdAt = now.plusSeconds(3600),
          refunds = Seq(makeRichRefund2(
            amount = 1000,
            balanceTransactionAmount = -1000,
            createdAt = now.plus(60, ChronoUnit.DAYS),
          ))
        ))))
      )
    )

    val entries = transaction.generateRawJournalEntries()
    NetAmount.compute(entries, endPeriod = invoice.paidAt) should be(Seq(
      NetAmount(990, Cash),
      NetAmount(10, Fees),
      NetAmount(1000, Revenue)
    ))
    NetAmount.compute(entries, lineItemId = Some(lineItems.head.base.id), endPeriod = invoice.paidAt) should be(Seq(
      NetAmount(50, Cash),
      NetAmount(50, Revenue)
    ))
    NetAmount.compute(entries, lineItemId = Some(lineItems(1).base.id), endPeriod = invoice.paidAt) should be(Seq(
      NetAmount(950, Cash),
      NetAmount(950, Revenue)
    ))

    NetAmount.compute(entries) should be(Seq(
      NetAmount(-10, Cash),
      NetAmount(10, Fees),
      NetAmount(1000, Refunds),
      NetAmount(1000, Revenue)
    ))
    NetAmount.compute(entries, lineItemId = Some(lineItems.head.base.id)) should be(Seq(
      NetAmount(50, Refunds),
      NetAmount(50, Revenue)
    ))
    NetAmount.compute(entries, lineItemId = Some(lineItems(1).base.id)) should be(Seq(
      NetAmount(950, Refunds),
      NetAmount(950, Revenue)
    ))
  }

  it("pays invoice settled in a different currency and incurs fx loss") {
    val now = Instant.now()
    val invoice = makeInvoice(
      total = 1000,
      currency = "usd",
      finalizedAt = Some(now),
      paidAt = Some(now.plusSeconds(3600)),
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          amount = 1000,
          startedAt = Some(now),
          endedAt = Some(now),
        ))),
        payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
          amount = 1000,
          balanceTransactionAmount = 900,
          balanceTransactionFeeAmount = 9,
          balanceTransactionCurrency = "eur"
        )))),
        finalizedAtExchangeRate = Some(ExchangeRate("usd", "eur", 100, 95)),
      ),
    )

    val entries = transaction.generateRawJournalEntries()
    NetAmount.compute(entries) should be(Seq(
      NetAmount(891, Cash, "eur"),
      NetAmount(9, Fees, "eur"),
      NetAmount(50, Loss, "eur"),
      NetAmount(950, Revenue, "eur")
    ))
  }

  it("pays invoice settled in a different currency and incurs fx gain") {
    val now = Instant.now()
    val invoice = makeInvoice(
      total = 1000,
      currency = "usd",
      finalizedAt = Some(now),
      paidAt = Some(now.plusSeconds(3600)),
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          amount = 1000,
          startedAt = Some(now),
          endedAt = Some(now),
        ))),
        payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
          amount = 1000,
          balanceTransactionAmount = 900,
          balanceTransactionFeeAmount = 9,
          balanceTransactionCurrency = "eur"
        )))),
        finalizedAtExchangeRate = Some(ExchangeRate("usd", "eur", 100, 84)),
      ),
    )

    val entries = transaction.generateRawJournalEntries()
    NetAmount.compute(entries) should be(Seq(
      NetAmount(891, Cash, "eur"),
      NetAmount(9, Fees, "eur"),
      NetAmount(-60, Loss, "eur"),
      NetAmount(840, Revenue, "eur")
    ))
  }

  it("marked uncollectible invoice") {
    val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
    val invoice = makeInvoice(
      total = 1200,
      finalizedAt = Some(now),
      markedUncollectibleAt = Some(now.plus(45, ChronoUnit.DAYS)),
    )

    val lineItem = makeRichInvoiceLineItem(base = makeInvoiceLineItem(
      invoiceId = invoice.id,
      amount = 1200,
      startedAt = Some(now),
      endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
    ))

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(lineItem),
      ),
    )

    val amounts = NetAmount.compute(transaction.generateRawJournalEntries())

    amounts should be(Seq(
      NetAmount(93, BadDebt),
      NetAmount(93, Revenue)
    ))
  }

  it("partially paid invoice marked uncollectible") {
    val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
    val invoice = makeInvoice(
      total = 1200,
      finalizedAt = Some(now),
      markedUncollectibleAt = Some(now.plus(45, ChronoUnit.DAYS)),
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          amount = 1200,
          startedAt = Some(now),
          endedAt = Some(now.plus(1200, ChronoUnit.DAYS)),
        ))),
        payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
          amount = 10,
          balanceTransactionAmount = 10,
          createdAt = now.plusSeconds(3600),
        ))))
      ),
    )

    val entries = transaction.generateRawJournalEntries()
    NetAmount.compute(entries) should be(Seq(
      NetAmount(21, BadDebt),
      NetAmount(10, Cash),
      NetAmount(31, Revenue)
    ))
  }

  it("voided invoice") {
    val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
    val invoice = makeInvoice(
      total = 1200,
      finalizedAt = Some(now),
      voidedAt = Some(now.plus(45, ChronoUnit.DAYS)),
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          amount = 1200,
          startedAt = Some(now),
          endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
        ))),
      ),
    )

    val amounts = NetAmount.compute(transaction.generateRawJournalEntries())

    amounts should be(Seq(
      NetAmount(93, Revenue),
      NetAmount(93, Voids)
    ))
  }

  it("refunded invoice") {
    val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
    val invoice = makeInvoice(
      total = 1200,
      finalizedAt = Some(now),
      paidAt = Some(now),
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          amount = 1200,
          startedAt = Some(now),
          endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
        ))),
        payments = Seq(makeRichInvoicePayment(
          charge = Some(makeRichCharge2(
            amount = invoice.total,
            balanceTransactionAmount = invoice.total,
            createdAt = now,
            refunds = Seq(makeRichRefund2(
              amount = 1200,
              balanceTransactionAmount = -1200,
              createdAt = now.plus(45, ChronoUnit.DAYS),
            ))
          ))
        ))
      ),
    )

    val amounts = NetAmount.compute(transaction.generateRawJournalEntries())

    amounts should be(Seq(
      NetAmount(93, Refunds),
      NetAmount(93, Revenue)
    ))
  }

  it("disputed invoice") {
    val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
    val invoice = makeInvoice(
      total = 1200,
      finalizedAt = Some(now),
      paidAt = Some(now),
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          amount = 1200,
          startedAt = Some(now),
          endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
        ))),
        payments = Seq(makeRichInvoicePayment(
          charge = Some(makeRichCharge2(
            amount = invoice.total,
            balanceTransactionAmount = invoice.total,
            createdAt = now,
            disputes = Seq(makeRichDispute2(
              amount = 1200,
              balanceTransactionAmount = -1200,
              createdAt = now.plus(45, ChronoUnit.DAYS),
            ))
          ))
        ))
      ),
    )

    val amounts = NetAmount.compute(transaction.generateRawJournalEntries())

    amounts should be(Seq(
      NetAmount(93, Disputes),
      NetAmount(93, Revenue)
    ))
  }

  it("invoice with customer balance used") {
    val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
    val invoice = makeInvoice(
      total = 1000,
      finalizedAt = Some(now),
      startingBalance = Some(-500),
      endingBalance = Some(-400)
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
          amount = 1000,
          startedAt = Some(now),
          endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
        ))),
        customerBalanceTransactions = Seq(makeCustomerBalanceTransaction(
          amount = 100,
          `type` = "applied_from_invoice",
          invoiceId = Some(invoice.id),
          created = now,
        ))
      ),
    )

    val amounts = NetAmount.compute(transaction.generateRawJournalEntries())

    amounts should be(Seq(
      NetAmount(900, AccountsReceivable),
      NetAmount(-100, CustomerBalance),
      NetAmount(1000, Revenue)
    ))
  }

  it("invoice with customer balance used and void") {
    val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
    val voidedAt = java.time.Instant.parse("2025-02-15T00:00:00Z")
    val invoice = makeInvoice(
      total = 1000,
      finalizedAt = Some(now),
      startingBalance = Some(-500),
      endingBalance = Some(-400),
      voidedAt = Some(voidedAt)
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          amount = 1000,
          startedAt = Some(now),
          endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
        ))),
        customerBalanceTransactions = Seq(
          makeCustomerBalanceTransaction(
            amount = 100,
            `type` = "applied_from_invoice",
            invoiceId = Some(invoice.id),
            created = now,
          ),
          makeCustomerBalanceTransaction(
            amount = -100,
            `type` = "unapplied_from_invoice",
            invoiceId = Some(invoice.id),
            created = voidedAt,
          )
        )
      ),
    )

    val entries = transaction.generateRawJournalEntries()
    val amounts = NetAmount.compute(entries)

    amounts should be(Seq(
      NetAmount(78, Revenue),
      NetAmount(78, Voids)
    ))
  }

  it("invoice with payment record (out of band assets)") {
    val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
    val invoice = makeInvoice(
      total = 1000,
      finalizedAt = Some(now),
      paidAt = Some(now.plusSeconds(3600)),
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          amount = 1000,
          startedAt = Some(now),
          endedAt = Some(now.plus(1, ChronoUnit.DAYS)),
        ))),
        payments = Seq(makeRichInvoicePayment(
          base = makeInvoicePayment(
            amountPaid = Some(1000),
            invoiceId = invoice.id,
            paymentRecordId = Some("pr_1234"),
            paidAt = Some(now.plusSeconds(3600)),
          )
        ))
      ),
    )

    val entries = transaction.generateRawJournalEntries()
    NetAmount.compute(entries) should be(Seq(
      NetAmount(1000, OutOfBandAssets),
      NetAmount(1000, Revenue)
    ))
  }

  it("negative invoice offset with customer balance") {
    val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
    val invoice = makeInvoice(
      total = -1000,
      finalizedAt = Some(now),
      startingBalance = Some(0),
      endingBalance = Some(-1000),
    )

    val transaction = makeProcessInvoice(
      transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
      invoice = makeRichInvoice(
        base = invoice,
        lineItems = Seq(
          makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = -1200,
            startedAt = Some(now),
            endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
          )),
          makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 200,
            startedAt = Some(now),
            endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
          )),
        ),
        customerBalanceTransactions = Seq(makeCustomerBalanceTransaction(
          amount = -1000,
          `type` = "invoice_too_small",
          invoiceId = Some(invoice.id),
          created = now,
        ))
      ),
    )

    val amounts = NetAmount.compute(transaction.generateRawJournalEntries())

    amounts should be(Seq(
      NetAmount(1000, CustomerBalance),
      NetAmount(-1000, Revenue)
    ))
  }

  describe("tax") {
    it("books inclusive and exclusive tax, then partially refunds, then fails the refund, then issue a full refund") {
      val now = java.time.Instant.parse("2026-02-15T00:00:00Z")
      val paidAt = now.plusSeconds(3600)
      val invoice = makeInvoice(
        total = 1200,
        currency = "usd",
        finalizedAt = Some(now),
        paidAt = Some(paidAt),
      )

      var transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(
            base = makeInvoiceLineItem(
              invoiceId = invoice.id,
              amount = 1000,
              currency = "usd",
              startedAt = Some(java.time.Instant.parse("2026-01-14T00:00:00Z")),
              endedAt = Some(java.time.Instant.parse("2026-02-20T00:00:00Z")),
            ),
            // amount 1000 = 900 revenue + 100 inclusive tax; a 200 exclusive tax is added on top, for a 1200 total.
            taxes = Seq(
              makeInvoiceLineItemTax(rank = 0, amount = 100, taxBehaviour = "inclusive"),
              makeInvoiceLineItemTax(rank = 1, amount = 200, taxBehaviour = "exclusive"),
            ),
          )),
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 1200,
            balanceTransactionAmount = 1200,
            createdAt = paidAt,
            refunds = Seq(
              // A 400 partial refund that later fails.
              makeRichRefund2(
                amount = 400,
                balanceTransactionAmount = -400,
                createdAt = java.time.Instant.parse("2026-03-10T00:00:00Z"),
                failureBalanceTransactionAmount = Some(400),
                failureBalanceTransactionCreatedAt = Some(java.time.Instant.parse("2026-04-10T00:00:00Z")),
              ),
              // A full 1200 refund.
              makeRichRefund2(
                amount = 1200,
                balanceTransactionAmount = -1200,
                createdAt = java.time.Instant.parse("2026-05-10T00:00:00Z"),
              ),
            ),
          ))))
        ),
      )

      val entries = transaction.generateRawJournalEntries()
      NetAmount.compute(entries, endPeriod = Some(paidAt)) should be(Seq(
        NetAmount(1200, Cash),
        NetAmount(900, Revenue),
        NetAmount(300, TaxLiability),
      ))
      NetAmount.compute(entries, endPeriod = Some(java.time.Instant.parse("2026-03-31T00:00:00Z"))) should be(Seq(
        NetAmount(800, Cash),
        NetAmount(300, Refunds),
        NetAmount(900, Revenue),
        NetAmount(200, TaxLiability),
      ))
      NetAmount.compute(entries, endPeriod = Some(java.time.Instant.parse("2026-04-30T00:00:00Z"))) should be(Seq(
        NetAmount(1200, Cash),
        NetAmount(400, Recoverables),
        NetAmount(300, Refunds),
        NetAmount(900, Revenue),
        NetAmount(200, TaxLiability),
      ))
      NetAmount.compute(entries) should be(Seq(
        NetAmount(900, Refunds),
        NetAmount(900, Revenue),
      ))
    }

    it("books tax, receives a partial payment, then marks the invoice uncollectible") {
      val now = java.time.Instant.parse("2026-02-15T00:00:00Z")
      val paidAt = now.plusSeconds(3600)
      val invoice = makeInvoice(
        total = 1200,
        currency = "usd",
        finalizedAt = Some(now),
        markedUncollectibleAt = Some(java.time.Instant.parse("2026-03-10T00:00:00Z")),
      )

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(
            base = makeInvoiceLineItem(
              invoiceId = invoice.id,
              amount = 1000,
              currency = "usd",
              startedAt = Some(java.time.Instant.parse("2026-01-14T00:00:00Z")),
              endedAt = Some(java.time.Instant.parse("2026-02-20T00:00:00Z")),
            ),
            taxes = Seq(
              makeInvoiceLineItemTax(rank = 0, amount = 100, taxBehaviour = "inclusive"),
              makeInvoiceLineItemTax(rank = 1, amount = 200, taxBehaviour = "exclusive"),
            ),
          )),
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 400,
            balanceTransactionAmount = 400,
            createdAt = paidAt,
          ))))
        ),
      )

      val entries = transaction.generateRawJournalEntries()

      NetAmount.compute(entries, endPeriod = Some(paidAt)) should be(Seq(
        NetAmount(800, AccountsReceivable),
        NetAmount(400, Cash),
        NetAmount(900, Revenue),
        NetAmount(300, TaxLiability),
      ))
      NetAmount.compute(entries) should be(Seq(
        NetAmount(600, BadDebt),
        NetAmount(400, Cash),
        NetAmount(900, Revenue),
        NetAmount(100, TaxLiability),
      ))
    }
  }

  describe("underpayment") {
    it("partially pays the invoice, marks the invoice as paid, then later pays the exact underpaid amount") {
      val now = Instant.parse("2026-02-15T00:00:00Z")
      val invoice = makeInvoice(
        total = 1200,
        currency = "usd",
        finalizedAt = Some(now),
      )

      val lineItem = makeRichInvoiceLineItem(
        base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          amount = 1000,
          currency = "usd",
          startedAt = Some(java.time.Instant.parse("2026-01-14T00:00:00Z")),
          endedAt = Some(java.time.Instant.parse("2026-02-20T00:00:00Z")),
        ),
        // amount 1000 = 900 revenue + 100 inclusive tax; a 200 exclusive tax is added on top, for a 1200 total.
        taxes = Seq(
          makeInvoiceLineItemTax(rank = 0, amount = 100, taxBehaviour = "inclusive"),
          makeInvoiceLineItemTax(rank = 1, amount = 200, taxBehaviour = "exclusive"),
        ),
      )

      var transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(lineItem),
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 400,
            balanceTransactionAmount = 400,
            createdAt = now.plusSeconds(3600),
          ))))
        ),
      )
      // 400 of the 1200 charge collected; 800 still owed as AR (900 revenue + 300 tax).
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(800, AccountsReceivable),
        NetAmount(400, Cash),
        NetAmount(900, Revenue),
        NetAmount(300, TaxLiability),
      ))

      transaction = transaction.copy(
        invoice = transaction.invoice.copy(
          base = transaction.invoice.base.copy(paidAt = Some(java.time.Instant.parse("2026-03-10T00:00:00Z")))
        )
      )
      // Marking paid writes the unpaid 800 off as an underpayment. Unlike bad debt, the tax liability is
      // kept in full (the tax is still owed).
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(400, Cash),
        NetAmount(900, Revenue),
        NetAmount(300, TaxLiability),
        NetAmount(800, Underpayment),
      ))

      transaction = transaction.copy(
        invoice = transaction.invoice.copy(
          payments = transaction.invoice.payments ++ Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 800,
            balanceTransactionAmount = 800,
            createdAt = Instant.parse("2026-04-10T00:00:00Z")
          ))))
        )
      )

      val entries = transaction.generateRawJournalEntries()
      NetAmount.compute(entries) should be(Seq(
        NetAmount(1200, Cash),
        NetAmount(800, Recoverables),
        NetAmount(900, Revenue),
        NetAmount(300, TaxLiability),
        NetAmount(800, Underpayment),
      ))
    }
  }

  describe("unbilled revenue") {
    it("unbilled revenue (positive line items). No invoice item") {
      val now = Instant.parse("2026-03-01T00:00:00Z")
      val invoice = makeInvoice(
        total = 222,
        finalizedAt = Some(now),
      )
      val lineItems = Seq(
        makeRichInvoiceLineItem(base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          amount = 149,
          startedAt = Some(now.minus(59, ChronoUnit.DAYS)),
          endedAt = Some(now.plus(90, ChronoUnit.DAYS)),
        )),
        makeRichInvoiceLineItem(base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          amount = 73,
          startedAt = Some(now.minus(28, ChronoUnit.DAYS)),
          endedAt = Some(now.plus(45, ChronoUnit.DAYS)),
        )),
      )

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = lineItems,
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 222,
            balanceTransactionAmount = 222,
            createdAt = now.plus(32, ChronoUnit.DAYS)
          ))))
        )
      )

      val entries = transaction.generateRawJournalEntries()
      NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(invoice.finalizedAt.get).minus(1, ChronoUnit.DAYS))) should be(Seq(
        NetAmount(87, Revenue),
        NetAmount(222, UnbilledAccountsReceivable),
        NetAmount(135, UnbilledDeferredRevenue),
      ))
      NetAmount.compute(entries, lineItemId = Some(lineItems.head.base.id), endPeriod = Some(getAccountingPeriod(invoice.finalizedAt.get).minus(1, ChronoUnit.DAYS))) should be(Seq(
        NetAmount(59, Revenue),
        NetAmount(149, UnbilledAccountsReceivable),
        NetAmount(90, UnbilledDeferredRevenue),
      ))
      NetAmount.compute(entries, lineItemId = Some(lineItems(1).base.id), endPeriod = Some(getAccountingPeriod(invoice.finalizedAt.get).minus(1, ChronoUnit.DAYS))) should be(Seq(
        NetAmount(28, Revenue),
        NetAmount(73, UnbilledAccountsReceivable),
        NetAmount(45, UnbilledDeferredRevenue),
      ))

      NetAmount.compute(entries, endPeriod = Some(invoice.finalizedAt.get)) should be(Seq(
        NetAmount(222, AccountsReceivable),
        NetAmount(73, DeferredRevenue),
        NetAmount(149, Revenue),
      ))
      NetAmount.compute(entries, lineItemId = Some(lineItems.head.base.id), endPeriod = Some(invoice.finalizedAt.get)) should be(Seq(
        NetAmount(149, AccountsReceivable),
        NetAmount(59, DeferredRevenue),
        NetAmount(90, Revenue),
      ))
      NetAmount.compute(entries, lineItemId = Some(lineItems(1).base.id), endPeriod = Some(invoice.finalizedAt.get)) should be(Seq(
        NetAmount(73, AccountsReceivable),
        NetAmount(14, DeferredRevenue),
        NetAmount(59, Revenue),
      ))

      NetAmount.compute(entries) should be(Seq(
        NetAmount(222, Cash),
        NetAmount(222, Revenue),
      ))
      NetAmount.compute(entries, lineItemId = Some(lineItems.head.base.id)) should be(Seq(
        NetAmount(149, Cash),
        NetAmount(149, Revenue),
      ))
      NetAmount.compute(entries, lineItemId = Some(lineItems(1).base.id)) should be(Seq(
        NetAmount(73, Cash),
        NetAmount(73, Revenue),
      ))
    }

    it("unbilled revenue (positive and negative line items). No invoice item") {
      val now = Instant.now()
      val invoice = makeInvoice(
        total = 800,
        finalizedAt = Some(now),
      )
      val lineItems = Seq(
        makeRichInvoiceLineItem(base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          amount = 1000,
          startedAt = Some(now.minus(90, ChronoUnit.DAYS)),
          endedAt = Some(now.plus(90, ChronoUnit.DAYS)),
        )),
        makeRichInvoiceLineItem(base = makeInvoiceLineItem(
          invoiceId = invoice.id,
          amount = -200,
          startedAt = Some(now.minus(45, ChronoUnit.DAYS)),
          endedAt = Some(now.plus(45, ChronoUnit.DAYS)),
        )),
      )

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = lineItems,
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 800,
            balanceTransactionAmount = 800,
            createdAt = now.plus(31, ChronoUnit.DAYS)
          ))))
        )
      )

      val entries = transaction.generateRawJournalEntries()
      NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(invoice.finalizedAt.get).minus(1, ChronoUnit.DAYS))) should be(Seq(
        NetAmount(330, Revenue),
        NetAmount(800, UnbilledAccountsReceivable),
        NetAmount(470, UnbilledDeferredRevenue),
      ))
      NetAmount.compute(entries, lineItemId = Some(lineItems.head.base.id), endPeriod = Some(getAccountingPeriod(invoice.finalizedAt.get).minus(1, ChronoUnit.DAYS))) should be(Seq(
        NetAmount(383, Revenue),
        NetAmount(1000, UnbilledAccountsReceivable),
        NetAmount(617, UnbilledDeferredRevenue),
      ))
      NetAmount.compute(entries, lineItemId = Some(lineItems(1).base.id), endPeriod = Some(getAccountingPeriod(invoice.finalizedAt.get).minus(1, ChronoUnit.DAYS))) should be(Seq(
        NetAmount(-53, Revenue),
        NetAmount(-200, UnbilledAccountsReceivable),
        NetAmount(-147, UnbilledDeferredRevenue),
      ))

      NetAmount.compute(entries, endPeriod = Some(invoice.finalizedAt.get)) should be(Seq(
        NetAmount(800, AccountsReceivable),
        NetAmount(371, DeferredRevenue),
        NetAmount(429, Revenue),
      ))
      NetAmount.compute(entries, lineItemId = Some(lineItems.head.base.id), endPeriod = Some(invoice.finalizedAt.get)) should be(Seq(
        NetAmount(1000, AccountsReceivable),
        NetAmount(451, DeferredRevenue),
        NetAmount(549, Revenue),
      ))
      NetAmount.compute(entries, lineItemId = Some(lineItems(1).base.id), endPeriod = Some(invoice.finalizedAt.get)) should be(Seq(
        NetAmount(-200, AccountsReceivable),
        NetAmount(-80, DeferredRevenue),
        NetAmount(-120, Revenue),
      ))

      transaction.generateRawJournalEntries().sortBy(_.accountingPeriod).foreach { e =>
        println(s"period: ${e.accountingPeriod},debit: ${e.debit}, credit: ${e.credit}, amount: ${e.settlementAmount}")
      }
      NetAmount.compute(entries) should be(Seq(
        NetAmount(800, Cash),
        NetAmount(800, Revenue),
      ))
      NetAmount.compute(entries, lineItemId = Some(lineItems.head.base.id)) should be(Seq(
        NetAmount(1000, Cash),
        NetAmount(1000, Revenue),
      ))
      NetAmount.compute(entries, lineItemId = Some(lineItems(1).base.id)) should be(Seq(
        NetAmount(-200, Cash),
        NetAmount(-200, Revenue),
      ))
    }

    it("unbilled revenue with invoice item. No fx loss. With tax and discount") {
      val now = Instant.now()
      val invoice = makeInvoice(
        total = 900,
        currency = "usd",
        finalizedAt = Some(now),
      )

      val lineItems = Seq(
        makeRichInvoiceLineItem(
          base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1000,
            startedAt = Some(now.minus(90, ChronoUnit.DAYS)),
            endedAt = Some(now.plus(90, ChronoUnit.DAYS)),
          ),
          // amount 1000, less a 100 discount and a 90 inclusive tax => 810 net revenue + 90 tax = 900 total.
          pretaxCreditAmounts = Seq(makeDiscountPretaxCreditAmount(amount = 100)),
          taxes = Seq(makeInvoiceLineItemTax(amount = 90, taxBehaviour = "inclusive")),
          invoiceItem = Some(makeRichInvoiceItem(
            base = makeInvoiceItem(
              amount = 1000,
              currency = "usd",
              createdAt = now.minus(90, ChronoUnit.DAYS),
              startedAt = Some(now.minus(90, ChronoUnit.DAYS)),
              endedAt = Some(now.plus(90, ChronoUnit.DAYS)),
            ),
            discounts = Seq(makeRichDiscount(coupon = Some(makeCoupon(amountOff = Some(100))))),
            taxRates = Seq(makeTaxRate(inclusive = true, rateType = Some("flat_amount"), flatAmount = Some(90))),
          ))
        ),
      )

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = lineItems,
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 900,
            balanceTransactionAmount = 900,
            createdAt = now.plus(31, ChronoUnit.DAYS)
          ))))
        )
      )

      val entries = transaction.generateRawJournalEntries()

      // Before finalization: the invoice item's net revenue (810) accrues as unbilled; the pre-invoice
      // portion (310) is recognized and the rest (500) sits in unbilled deferred revenue.
      NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(invoice.finalizedAt.get).minus(1, ChronoUnit.DAYS))) should be(Seq(
        NetAmount(309, Revenue),
        NetAmount(810, UnbilledAccountsReceivable),
        NetAmount(501, UnbilledDeferredRevenue),
      ))

      // At finalization: AR is the 900 charge (810 net revenue + 90 tax); revenue recognized to date is
      // 445 with 365 still deferred, and the 90 tax is booked to the tax liability.
      NetAmount.compute(entries, endPeriod = Some(invoice.finalizedAt.get)) should be(Seq(
        NetAmount(900, AccountsReceivable),
        NetAmount(365, DeferredRevenue),
        NetAmount(445, Revenue),
        NetAmount(90, TaxLiability),
      ))

      // Fully paid: 900 collected as cash, 810 revenue recognized, 90 tax liability.
      NetAmount.compute(entries) should be(Seq(
        NetAmount(900, Cash),
        NetAmount(810, Revenue),
        NetAmount(90, TaxLiability),
      ))
    }

    it("unbilled revenue where the invoice item exchange rate is lower than the invoice finalization exchange rate (with invoice item)") {
      val now = Instant.now()
      val invoice = makeInvoice(
        total = 1000,
        currency = "usd",
        finalizedAt = Some(now),
      )

      val lineItems = Seq(
        makeRichInvoiceLineItem(
          base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1000,
            startedAt = Some(now.minus(90, ChronoUnit.DAYS)),
            endedAt = Some(now.plus(90, ChronoUnit.DAYS)),
          ),
          invoiceItem = Some(makeRichInvoiceItem(
            base = makeInvoiceItem(
              amount = 1000,
              currency = "usd",
              createdAt = now.minus(90, ChronoUnit.DAYS),
              startedAt = Some(now.minus(90, ChronoUnit.DAYS)),
              endedAt = Some(now.plus(90, ChronoUnit.DAYS)),
            ),
            createdAtExchangeRate = Some(ExchangeRate("usd", "eur", 100, 80))
          ))
        ),
      )

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = lineItems,
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 1000,
            balanceTransactionAmount = 900,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(31, ChronoUnit.DAYS)
          )))),
          finalizedAtExchangeRate = Some(ExchangeRate("usd", "eur", 100, 90)),
        ),
      )

      val entries = transaction.generateRawJournalEntries()
      NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(invoice.finalizedAt.get).minus(1, ChronoUnit.DAYS))) should be(Seq(
        NetAmount(306, Revenue, "eur"),
        NetAmount(800, UnbilledAccountsReceivable, "eur"),
        NetAmount(494, UnbilledDeferredRevenue, "eur"),
      ))
      NetAmount.compute(entries, endPeriod = Some(invoice.finalizedAt.get)) should be(Seq(
        NetAmount(900, AccountsReceivable, "eur"),
        NetAmount(406, DeferredRevenue, "eur"),
        NetAmount(494, Revenue, "eur"),
      ))
      NetAmount.compute(entries) should be(Seq(
        NetAmount(900, Cash, "eur"),
        NetAmount(900, Revenue, "eur"),
      ))
    }

    it("unbilled revenue where the invoice item exchange rate is higher than the invoice finalization exchange rate (with invoice item)") {
      val now = Instant.now()
      val invoice = makeInvoice(
        total = 1000,
        currency = "usd",
        finalizedAt = Some(now),
      )

      val lineItems = Seq(
        makeRichInvoiceLineItem(
          base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1000,
            startedAt = Some(now.minus(90, ChronoUnit.DAYS)),
            endedAt = Some(now.plus(90, ChronoUnit.DAYS)),
          ),
          invoiceItem = Some(makeRichInvoiceItem(
            base = makeInvoiceItem(
              amount = 1000,
              currency = "usd",
              createdAt = now.minus(90, ChronoUnit.DAYS),
              startedAt = Some(now.minus(90, ChronoUnit.DAYS)),
              endedAt = Some(now.plus(90, ChronoUnit.DAYS)),
            ),
            createdAtExchangeRate = Some(ExchangeRate("usd", "eur", 100, 90))
          ))
        ),
      )

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = lineItems,
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 1000,
            balanceTransactionAmount = 800,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(31, ChronoUnit.DAYS)
          )))),
          finalizedAtExchangeRate = Some(ExchangeRate("usd", "eur", 100, 80)),
        ),
      )

      val entries = transaction.generateRawJournalEntries()
      NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(invoice.finalizedAt.get).minus(1, ChronoUnit.DAYS))) should be(Seq(
        NetAmount(344, Revenue, "eur"),
        NetAmount(900, UnbilledAccountsReceivable, "eur"),
        NetAmount(556, UnbilledDeferredRevenue, "eur"),
      ))

      NetAmount.compute(entries, endPeriod = Some(invoice.finalizedAt.get)) should be(Seq(
        NetAmount(800, AccountsReceivable, "eur"),
        NetAmount(360, DeferredRevenue, "eur"),
        NetAmount(440, Revenue, "eur"),
      ))

      NetAmount.compute(entries) should be(Seq(
        NetAmount(800, Cash, "eur"),
        NetAmount(800, Revenue, "eur"),
      ))
    }
  }

  describe("unbilled usage") {
    it("has unbilled usage line item") {
      val now = java.time.Instant.parse("2026-02-15T00:00:00Z")
      val invoice = makeInvoice(
        total = 1045,
        currency = "usd",
        finalizedAt = Some(now),
      )

      val price = makeRichPrice(base = makePrice(billingScheme = "per_unit", unitAmount = 5, currency = "usd", recurringUsageType = Some("metered")))

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(
            makeRichInvoiceLineItem(
              base = makeInvoiceLineItem(
                invoiceId = invoice.id,
                amount = 750,
                currency = "usd",
                startedAt = Some(java.time.Instant.parse("2026-01-14T00:00:00Z")),
                endedAt = Some(java.time.Instant.parse("2026-02-20T00:00:00Z")),
                // A defined pricingUnitAmountDecimal marks this as the usage-fee line item.
                pricingUnitAmountDecimal = Some("5"),
              ),
              price = Some(price),
              // A 10% discount and a 10% inclusive tax on the usage, carried by the subscription item.
              subscriptionItem = Some(makeSubscriptionItem()),
              meterEventSummaries = Seq(
                // 100 units in January (recognized before the invoice period)
                makeMeterEventSummary(aggregatedValue = 100, startTime = Instant.parse("2026-01-16T00:00:00Z"), endTime = Instant.parse("2026-01-17T00:00:00Z")),
                // 50 units in February (recognized within the invoice period)
                makeMeterEventSummary(aggregatedValue = 50, startTime = Instant.parse("2026-02-16T00:00:00Z"), endTime = Instant.parse("2026-02-17T00:00:00Z")),
              ),
              pretaxCreditAmounts = Seq(makeDiscountPretaxCreditAmount(amount = 75)),
              taxes = Seq(makeInvoiceLineItemTax(amount = 67, taxBehaviour = "inclusive")),
            ),
            makeRichInvoiceLineItem(
              base = makeInvoiceLineItem(
                invoiceId = invoice.id,
                amount = 370,
                currency = "usd",
                startedAt = Some(java.time.Instant.parse("2026-01-14T00:00:00Z")),
                endedAt = Some(java.time.Instant.parse("2026-02-20T00:00:00Z")),
                rank = 1,
                // A None pricingUnitAmountDecimal marks this as the flat-fee line item; it amortizes over the period.
                pricingUnitAmountDecimal = None,
              ),
              price = Some(price),
            ),
          ),
        ),
      )

      val entries = transaction.generateRawJournalEntries()

      // TODO: estimate this properly. We may need to compute the whole period without discounts/taxes and then amortize the discounts/taxes
      // Before the invoice period: January usage (500 gross, less 50 discount and 45 inclusive tax = 405)
      // plus the January portion of the amortized flat fee (370 * 18/37 = 180); the rest (190) is deferred.
      NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(invoice.finalizedAt.get).minus(1, ChronoUnit.DAYS))) should be(Seq(
        NetAmount(585, Revenue),
        NetAmount(775, UnbilledAccountsReceivable),
        NetAmount(190, UnbilledDeferredRevenue),
      ))

      // After finalization: net usage (750 gross - 75 discount - 67 inclusive tax = 608) plus the flat
      // fee (370) = 978 booked to AR and Revenue.
      NetAmount.compute(entries) should be(Seq(
        NetAmount(1045, AccountsReceivable),
        NetAmount(978, Revenue),
        NetAmount(67, TaxLiability),
      ))
    }

    it("has unbilled usage line item with paid and promotional credit grants and later void") {
      val now = java.time.Instant.parse("2026-02-15T00:00:00Z")
      val invoice = makeInvoice(
        total = 570,
        currency = "usd",
        finalizedAt = Some(now),
        voidedAt = Some(now.plus(45, ChronoUnit.DAYS)),
      )

      val price = makeRichPrice(base = makePrice(billingScheme = "per_unit", unitAmount = 5, currency = "usd", recurringUsageType = Some("metered")))

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(
            makeRichInvoiceLineItem(
              base = makeInvoiceLineItem(
                invoiceId = invoice.id,
                amount = 770,
                currency = "usd",
                startedAt = Some(java.time.Instant.parse("2026-01-14T00:00:00Z")),
                endedAt = Some(java.time.Instant.parse("2026-02-20T00:00:00Z")),
                pricingUnitAmountDecimal = Some("5"),
              ),
              price = Some(price),
              subscriptionItem = Some(makeSubscriptionItem()),
              meterEventSummaries = Seq(
                makeMeterEventSummary(aggregatedValue = 100, startTime = Instant.parse("2026-01-16T00:00:00Z"), endTime = Instant.parse("2026-01-17T00:00:00Z")),
                makeMeterEventSummary(aggregatedValue = 50, startTime = Instant.parse("2026-02-16T00:00:00Z"), endTime = Instant.parse("2026-02-17T00:00:00Z")),
              ),
              taxes = Seq(makeInvoiceLineItemTax(amount = 20, taxBehaviour = "inclusive")),
              // A 100 paid credit grant and a 50 promotional credit grant applied to the usage.
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
              // Voiding the invoice returns the applied credits to their grants.
              creditBalanceTransactionsAppliedOnVoid = Seq(
                makeRichCreditBalanceTransaction(
                  base = makeCreditBalanceTransaction(`type` = "credit", creditAmount = Some(100), creditCurrency = Some("usd")),
                  creditGrant = Some(makeCreditGrant(category = "paid"))
                ),
                makeRichCreditBalanceTransaction(
                  base = makeCreditBalanceTransaction(`type` = "credit", creditAmount = Some(50), creditCurrency = Some("usd")),
                  creditGrant = Some(makeCreditGrant(category = "promotional"))
                ),
              ),
            ),
          ),
        ),
      )

      val entries = transaction.generateRawJournalEntries()
      NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(invoice.voidedAt.get).minus(1, ChronoUnit.DAYS))) should be(Seq(
        NetAmount(570, AccountsReceivable),
        NetAmount(-100, PaidCreditGrants),
        NetAmount(-50, PromotionalCreditGrants),
        NetAmount(700, Revenue),
        NetAmount(20, TaxLiability),
      ))

      NetAmount.compute(entries) should be(Seq(
        NetAmount(700, Revenue),
        NetAmount(700, Voids),
      ))
    }

    it("has unbilled usage line item with a lower exchange rate") {
      val now = java.time.Instant.parse("2026-02-15T00:00:00Z")
      val invoice = makeInvoice(
        total = 1120,
        currency = "usd",
        finalizedAt = Some(now),
      )

      val price = makeRichPrice(base = makePrice(billingScheme = "per_unit", unitAmount = 5, currency = "usd", recurringUsageType = Some("metered")))

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(
            makeRichInvoiceLineItem(
              base = makeInvoiceLineItem(
                invoiceId = invoice.id,
                amount = 750,
                currency = "usd",
                startedAt = Some(java.time.Instant.parse("2026-01-14T00:00:00Z")),
                endedAt = Some(java.time.Instant.parse("2026-02-20T00:00:00Z")),
                // A defined pricingUnitAmountDecimal marks this as the usage-fee line item.
                pricingUnitAmountDecimal = Some("5"),
              ),
              price = Some(price),
              subscriptionItem = Some(makeSubscriptionItem()),
              meterEventSummaries = Seq(
                makeMeterEventSummary(aggregatedValue = 100, startTime = Instant.parse("2026-01-16T00:00:00Z"), endTime = Instant.parse("2026-01-17T00:00:00Z")),
                makeMeterEventSummary(aggregatedValue = 50, startTime = Instant.parse("2026-02-16T00:00:00Z"), endTime = Instant.parse("2026-02-17T00:00:00Z")),
              ),
              // The usage started at a lower rate than the invoice finalization rate.
              startedAtExchangeRate = ExchangeRate("usd", "eur", 100, 80),
            ),
            makeRichInvoiceLineItem(
              base = makeInvoiceLineItem(
                invoiceId = invoice.id,
                amount = 370,
                currency = "usd",
                startedAt = Some(java.time.Instant.parse("2026-01-14T00:00:00Z")),
                endedAt = Some(java.time.Instant.parse("2026-02-20T00:00:00Z")),
                rank = 1,
                // A None pricingUnitAmountDecimal marks this as the flat-fee line item.
                pricingUnitAmountDecimal = None,
              ),
              price = Some(price),
              startedAtExchangeRate = ExchangeRate("usd", "eur", 100, 80),
            ),
          ),
          finalizedAtExchangeRate = Some(ExchangeRate("usd", "eur", 100, 90)),
        ),
      )

      val entries = transaction.generateRawJournalEntries()

      NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(invoice.finalizedAt.get).minus(1, ChronoUnit.DAYS))) should be(Seq(
        NetAmount(544, Revenue, "eur"),
        NetAmount(696, UnbilledAccountsReceivable, "eur"),
        NetAmount(152, UnbilledDeferredRevenue, "eur"),
      ))

      // At finalization everything is valued at 0.90: 1120 * 0.90 = 1008.
      NetAmount.compute(entries) should be(Seq(
        NetAmount(1008, AccountsReceivable, "eur"),
        NetAmount(1008, Revenue, "eur"),
      ))
    }

    it("has unbilled usage line item with a higher exchange rate") {
      val now = java.time.Instant.parse("2026-02-15T00:00:00Z")
      val invoice = makeInvoice(
        total = 1120,
        currency = "usd",
        finalizedAt = Some(now),
      )

      val price = makeRichPrice(base = makePrice(billingScheme = "per_unit", unitAmount = 5, currency = "usd", recurringUsageType = Some("metered")))

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(
            makeRichInvoiceLineItem(
              base = makeInvoiceLineItem(
                invoiceId = invoice.id,
                amount = 750,
                currency = "usd",
                startedAt = Some(java.time.Instant.parse("2026-01-14T00:00:00Z")),
                endedAt = Some(java.time.Instant.parse("2026-02-20T00:00:00Z")),
                // A defined pricingUnitAmountDecimal marks this as the usage-fee line item.
                pricingUnitAmountDecimal = Some("5"),
              ),
              price = Some(price),
              subscriptionItem = Some(makeSubscriptionItem()),
              meterEventSummaries = Seq(
                makeMeterEventSummary(aggregatedValue = 100, startTime = Instant.parse("2026-01-16T00:00:00Z"), endTime = Instant.parse("2026-01-17T00:00:00Z")),
                makeMeterEventSummary(aggregatedValue = 50, startTime = Instant.parse("2026-02-16T00:00:00Z"), endTime = Instant.parse("2026-02-17T00:00:00Z")),
              ),
              // The usage started at a higher rate than the invoice finalization rate.
              startedAtExchangeRate = ExchangeRate("usd", "eur", 100, 90),
            ),
            makeRichInvoiceLineItem(
              base = makeInvoiceLineItem(
                invoiceId = invoice.id,
                amount = 370,
                currency = "usd",
                startedAt = Some(java.time.Instant.parse("2026-01-14T00:00:00Z")),
                endedAt = Some(java.time.Instant.parse("2026-02-20T00:00:00Z")),
                rank = 1,
                // A None pricingUnitAmountDecimal marks this as the flat-fee line item.
                pricingUnitAmountDecimal = None,
              ),
              price = Some(price),
              startedAtExchangeRate = ExchangeRate("usd", "eur", 100, 90),
            ),
          ),
          finalizedAtExchangeRate = Some(ExchangeRate("usd", "eur", 100, 80)),
        ),
      )

      val entries = transaction.generateRawJournalEntries()

      NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(invoice.finalizedAt.get).minus(1, ChronoUnit.DAYS))) should be(Seq(
        NetAmount(612, Revenue, "eur"),
        NetAmount(783, UnbilledAccountsReceivable, "eur"),
        NetAmount(171, UnbilledDeferredRevenue, "eur"),
      ))

      // At finalization everything is valued at 0.80: 1120 * 0.80 = 896.
      NetAmount.compute(entries) should be(Seq(
        NetAmount(896, AccountsReceivable, "eur"),
        NetAmount(896, Revenue, "eur"),
      ))
    }
  }

  describe("refunds") {
    it("clean refund") {
      val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
      val invoice = makeInvoice(
        total = 1200,
        finalizedAt = Some(now),
        paidAt = Some(now.plusSeconds(3600)),
      )

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1200,
            startedAt = Some(now),
            endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
          ))),
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 1200,
            balanceTransactionAmount = 1200,
            createdAt = now.plusSeconds(3600),
            refunds = Seq(makeRichRefund2(
              amount = 1200,
              balanceTransactionAmount = -1200,
              createdAt = now.plus(45, ChronoUnit.DAYS),
            )),
          ))))
        ),
      )

      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(93, Refunds),
        NetAmount(93, Revenue),
      ))
    }

    it("refund settled in a different currency and incurs fx loss") {
      val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
      val invoice = makeInvoice(
        total = 1000,
        currency = "usd",
        finalizedAt = Some(now),
        paidAt = Some(now.plusSeconds(3600)),
      )

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1000,
            startedAt = Some(now),
            endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
          ))),
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 1000,
            balanceTransactionAmount = 950,
            balanceTransactionCurrency = "eur",
            createdAt = now.plusSeconds(3600),
            refunds = Seq(makeRichRefund2(
              amount = 1000,
              balanceTransactionAmount = -1000,
              balanceTransactionCurrency = "eur",
              createdAt = now.plus(45, ChronoUnit.DAYS),
            )),
          )))),
          finalizedAtExchangeRate = Some(ExchangeRate("usd", "eur", 100, 95)),
        ),
      )

      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(-50, Cash, "eur"),
        NetAmount(50, Loss, "eur"),
        NetAmount(74, Refunds, "eur"),
        NetAmount(74, Revenue, "eur"),
      ))
    }

    it("refund settled in a different currency and incurs fx gain") {
      val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
      val paidAt = now.plusSeconds(3600)
      val invoice = makeInvoice(
        total = 1000,
        currency = "usd",
        finalizedAt = Some(now),
        paidAt = Some(paidAt),
      )

      var transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1000,
            startedAt = Some(now),
            endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
          ))),
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 1000,
            balanceTransactionAmount = 950,
            balanceTransactionCurrency = "eur",
            createdAt = paidAt,
            refunds = Seq(makeRichRefund2(
              amount = 1000,
              balanceTransactionAmount = -900,
              balanceTransactionCurrency = "eur",
              createdAt = now.plus(45, ChronoUnit.DAYS),
            )),
          )))),
          finalizedAtExchangeRate = Some(ExchangeRate("usd", "eur", 100, 95)),
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(50, Cash, "eur"),
        NetAmount(-50, Loss, "eur"),
        NetAmount(74, Refunds, "eur"),
        NetAmount(74, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        invoice = transaction.invoice.copy(
          payments = Seq(transaction.invoice.payments.head.copy(
            charge = Some(transaction.invoice.payments.head.charge.get.copy(
              refunds = Seq(transaction.invoice.payments.head.charge.get.refunds.head.copy(
                failureBalanceTransaction = Some(makeBalanceTransaction(amount = 900, currency = "eur", createdAt = now.plus(46, ChronoUnit.DAYS))),
              ))
            ))
          )
        ))
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(950, Cash, "eur"),
        NetAmount(950, Recoverables, "eur"),
        NetAmount(74, Refunds, "eur"),
        NetAmount(74, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        invoice = transaction.invoice.copy(
          payments = Seq(transaction.invoice.payments.head.copy(
            charge = Some(transaction.invoice.payments.head.charge.get.copy(
              refunds = transaction.invoice.payments.head.charge.get.refunds ++ Seq(makeRichRefund2(
                amount = 1000,
                balanceTransactionAmount = -900,
                balanceTransactionCurrency = "eur",
                createdAt = now.plus(47, ChronoUnit.DAYS),
              ))
            ))
          )
          ))
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(50, Cash, "eur"),
        NetAmount(50, Recoverables, "eur"),
        NetAmount(74, Refunds, "eur"),
        NetAmount(74, Revenue, "eur"),
      ))
    }

    it("multiple payments with refunds before and after the invoice is paid") {
      val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
      val invoice = makeInvoice(
        total = 1200,
        finalizedAt = Some(now),
        paidAt = Some(now.plus(30, ChronoUnit.DAYS)),
      )

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1200,
            startedAt = Some(now),
            endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
          ))),
          payments = Seq(
            makeRichInvoicePayment(charge = Some(makeRichCharge2(
              amount = 500,
              balanceTransactionAmount = 500,
              createdAt = now.plus(5, ChronoUnit.DAYS),
              refunds = Seq(makeRichRefund2(
                amount = 200,
                balanceTransactionAmount = -200,
                createdAt = now.plus(10, ChronoUnit.DAYS),
              )),
            ))),
            makeRichInvoicePayment(charge = Some(makeRichCharge2(
              amount = 900,
              balanceTransactionAmount = 900,
              createdAt = now.plus(30, ChronoUnit.DAYS),
              refunds = Seq(makeRichRefund2(
                amount = 300,
                balanceTransactionAmount = -300,
                createdAt = now.plus(60, ChronoUnit.DAYS),
              )),
            ))),
          )
        ),
      )

      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(900, Cash),
        NetAmount(900, Revenue),
      ))
    }
  }

  describe("disputes") {
    it("clean dispute") {
      val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
      val invoice = makeInvoice(
        total = 1200,
        finalizedAt = Some(now),
        paidAt = Some(now.plusSeconds(3600)),
      )

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1200,
            startedAt = Some(now),
            endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
          ))),
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 1200,
            balanceTransactionAmount = 1200,
            createdAt = now.plusSeconds(3600),
            disputes = Seq(makeRichDispute2(
              amount = 1200,
              balanceTransactionAmount = -1200,
              createdAt = now.plus(45, ChronoUnit.DAYS),
            )),
          ))))
        ),
      )

      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(93, Disputes),
        NetAmount(93, Revenue),
      ))
    }

    it("dispute settled in a different currency and incurs fx loss") {
      val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
      val invoice = makeInvoice(
        total = 1000,
        currency = "usd",
        finalizedAt = Some(now),
        paidAt = Some(now.plusSeconds(3600)),
      )

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1000,
            startedAt = Some(now),
            endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
          ))),
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 1000,
            balanceTransactionAmount = 950,
            balanceTransactionCurrency = "eur",
            createdAt = now.plusSeconds(3600),
            disputes = Seq(makeRichDispute2(
              amount = 1000,
              balanceTransactionAmount = -1000,
              balanceTransactionCurrency = "eur",
              createdAt = now.plus(45, ChronoUnit.DAYS),
            )),
          )))),
          finalizedAtExchangeRate = Some(ExchangeRate("usd", "eur", 100, 95)),
        ),
      )

      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(-50, Cash, "eur"),
        NetAmount(74, Disputes, "eur"),
        NetAmount(50, Loss, "eur"),
        NetAmount(74, Revenue, "eur"),
      ))
    }

    it("dispute settled in a different currency and incurs fx gain") {
      val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
      val invoice = makeInvoice(
        total = 1000,
        currency = "usd",
        finalizedAt = Some(now),
        paidAt = Some(now.plusSeconds(3600)),
      )

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1000,
            startedAt = Some(now),
            endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
          ))),
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 1000,
            balanceTransactionAmount = 950,
            balanceTransactionCurrency = "eur",
            createdAt = now.plusSeconds(3600),
            disputes = Seq(makeRichDispute2(
              amount = 1000,
              balanceTransactionAmount = -900,
              balanceTransactionCurrency = "eur",
              createdAt = now.plus(45, ChronoUnit.DAYS),
            )),
          )))),
          finalizedAtExchangeRate = Some(ExchangeRate("usd", "eur", 100, 95)),
        ),
      )

      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(50, Cash, "eur"),
        NetAmount(74, Disputes, "eur"),
        NetAmount(-50, Loss, "eur"),
        NetAmount(74, Revenue, "eur"),
      ))
    }

    it("multiple disputes") {
      val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
      val invoice = makeInvoice(
        total = 1200,
        finalizedAt = Some(now),
        paidAt = Some(now.plusSeconds(3600)),
      )

      var transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1200,
            startedAt = Some(now),
            endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
          ))),
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 1200,
            balanceTransactionAmount = 1200,
            createdAt = now.plusSeconds(3600),
            disputes = Seq(makeRichDispute2(
              amount = 400,
              balanceTransactionAmount = -400,
              createdAt = now.plus(450, ChronoUnit.DAYS),
            )),
          ))))
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(800, Cash),
        NetAmount(400, Disputes),
        NetAmount(1200, Revenue),
      ))

      transaction = transaction.copy(
        invoice = transaction.invoice.copy(
          payments = Seq(makeRichInvoicePayment(charge = Some(transaction.invoice.payments.head.charge.head.copy(
            disputes = transaction.invoice.payments.head.charge.head.disputes ++ Seq(makeRichDispute2(
              amount = 300,
              balanceTransactionAmount = -300,
              createdAt = now.plus(600, ChronoUnit.DAYS),
            )),
          ))))
        )
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(500, Cash),
        NetAmount(700, Disputes),
        NetAmount(1200, Revenue),
      ))
    }

    it("wins a dispute, then gets disputed again partially") {
      val now = java.time.Instant.parse("2025-01-01T00:00:00Z")
      val invoice = makeInvoice(
        total = 1200,
        finalizedAt = Some(now),
        paidAt = Some(now.plusSeconds(3600)),
      )

      var transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1200,
            startedAt = Some(now),
            endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
          ))),
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 1200,
            balanceTransactionAmount = 1200,
            createdAt = now.plusSeconds(3600),
            disputes = Seq(makeRichDispute2(
              amount = 1200,
              balanceTransactionAmount = -1200,
              createdAt = now.plus(45, ChronoUnit.DAYS),
              wonBalanceTransactionAmount = Some(1200),
              wonBalanceTransactionCreatedAt = Some(now.plus(60, ChronoUnit.DAYS)),
            )),
          ))))
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(1200, Cash),
        NetAmount(93, Disputes),
        NetAmount(1200, Recoverables),
        NetAmount(93, Revenue),
      ))

      transaction = transaction.copy(
        invoice = transaction.invoice.copy(
          payments = Seq(makeRichInvoicePayment(charge = Some(transaction.invoice.payments.head.charge.head.copy(
            disputes = transaction.invoice.payments.head.charge.head.disputes ++ Seq(makeRichDispute2(
              amount = 400,
              balanceTransactionAmount = -400,
              createdAt = now.plus(90, ChronoUnit.DAYS),
            )),
          ))))
        )
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(800, Cash),
        NetAmount(93, Disputes),
        NetAmount(800, Recoverables),
        NetAmount(93, Revenue),
      ))
    }
  }

  describe("uncollectible") {
    it("unpaid") {
      val now = Instant.now()
      val invoice = makeInvoice(
        total = 1200,
        finalizedAt = Some(now),
        markedUncollectibleAt = Some(now.plus(90, ChronoUnit.DAYS)),
      )

      var transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1200,
            startedAt = Some(now),
            endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
          )))
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(210, BadDebt),
        NetAmount(210, Revenue),
      ))
    }

    it("partial payment, mark uncollectible, refunded, and void") {
      val now = Instant.parse("2025-01-01T00:00:00Z")
      val partialPaidAt = Instant.parse("2025-01-03T00:00:00Z")
      val firstRefundedAt = Instant.parse("2025-03-04T00:00:00Z")
      val secondRefundedAt = Instant.parse("2025-04-04T00:00:00Z")
      val invoice = makeInvoice(
        total = 1200,
        finalizedAt = Some(now),
        markedUncollectibleAt = Some(Instant.parse("2025-02-04T00:00:00Z")),
        voidedAt = Some(Instant.parse("2025-05-04T00:00:00Z"))
      )

      var transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1200,
            startedAt = Some(now),
            endedAt = Some(now.plus(1200, ChronoUnit.DAYS)),
          ))),
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 400,
            balanceTransactionAmount = 400,
            createdAt = partialPaidAt,
            refunds = Seq(
              makeRichRefund2(
                amount = 210,
                balanceTransactionAmount = -210,
                createdAt = firstRefundedAt,
              ),
              makeRichRefund2(
                amount = 190,
                balanceTransactionAmount = -190,
                createdAt = secondRefundedAt,
              )
            )
          ))))
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries(), endPeriod = Some(partialPaidAt)) should be(Seq(
        NetAmount(800, AccountsReceivable),
        NetAmount(400, Cash),
        NetAmount(1169, DeferredRevenue),
        NetAmount(31, Revenue),
      ))
      NetAmount.compute(transaction.generateRawJournalEntries(), endPeriod = Some(invoice.markedUncollectibleAt.get)) should be(Seq(
        NetAmount(400, Cash),
        NetAmount(341, DeferredRevenue),
        NetAmount(59, Revenue),
      ))
      // We refund from the back to front. Therefore, we cancel the deferred revenue first
      NetAmount.compute(transaction.generateRawJournalEntries(), endPeriod = Some(firstRefundedAt)) should be(Seq(
        NetAmount(190, Cash),
        NetAmount(100, DeferredRevenue),
        NetAmount(90, Revenue),
      ))
      NetAmount.compute(transaction.generateRawJournalEntries(), endPeriod = Some(secondRefundedAt)) should be(Seq(
        NetAmount(90, Refunds),
        NetAmount(90, Revenue),
      ))
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(90, Revenue),
        NetAmount(90, Voids),
      ))
    }
  }

  describe("void") {
    it("unpaid") {
      val now = Instant.now()
      val invoice = makeInvoice(
        total = 1200,
        finalizedAt = Some(now),
        voidedAt = Some(now.plus(90, ChronoUnit.DAYS)),
      )

      var transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1200,
            startedAt = Some(now),
            endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
          ))),
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(210, Revenue),
        NetAmount(210, Voids),
      ))
    }

    it("pays, refunds, and voids") {
      val now = Instant.now()
      val invoice = makeInvoice(
        total = 1200,
        finalizedAt = Some(now),
      )

      var transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1200,
            startedAt = Some(now),
            endedAt = Some(now.plus(400, ChronoUnit.DAYS)),
          ))),
          payments = Seq(makeRichInvoicePayment(charge = Some(makeRichCharge2(
            amount = 400,
            balanceTransactionAmount = 400,
            createdAt = now.plusSeconds(3600),
          ))))
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(800, AccountsReceivable),
        NetAmount(400, Cash),
        NetAmount(1200, Revenue),
      ))

      transaction = transaction.copy(
        invoice = transaction.invoice.copy(
          payments = Seq(makeRichInvoicePayment(charge = Some(transaction.invoice.payments.head.charge.head.copy(
            refunds = Seq(makeRichRefund2(
              amount = 190,
              balanceTransactionAmount = -190,
              createdAt = now.plus(90, ChronoUnit.DAYS),
            )),
          ))))
        )
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(990, AccountsReceivable),
        NetAmount(210, Cash),
        NetAmount(1200, Revenue),
      ))

      transaction = transaction.copy(
        invoice = transaction.invoice.copy(
          payments = Seq(makeRichInvoicePayment(charge = Some(transaction.invoice.payments.head.charge.head.copy(
            refunds = transaction.invoice.payments.head.charge.head.refunds ++ Seq(makeRichRefund2(
              amount = 210,
              balanceTransactionAmount = -210,
              createdAt = now.plus(91, ChronoUnit.DAYS),
            )),
          ))))
        )
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(1200, AccountsReceivable),
        NetAmount(1200, Revenue),
      ))

      transaction = transaction.copy(
        invoice = transaction.invoice.copy(
          base = transaction.invoice.base.copy(
            voidedAt = Some(now.plus(120, ChronoUnit.DAYS))
          )
        )
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(303, Revenue),
        NetAmount(303, Voids),
      ))
    }
  }

  describe("different timing between invoice payment and charge") {
    it("the charge occurred a month before the invoice payment") {
      val now = Instant.now()
      val chargeAt = now.plusSeconds(3600)
      val paidAt = chargeAt.plus(35, ChronoUnit.DAYS)

      val invoice = makeInvoice(
        total = 1000,
        finalizedAt = Some(now),
        paidAt = Some(paidAt),
      )

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1000,
            startedAt = Some(now),
            endedAt = Some(now),
          ))),
          payments = Seq(makeRichInvoicePayment(
            base = makeInvoicePayment(invoiceId = invoice.id, paidAt = Some(paidAt)),
            charge = Some(makeRichCharge2(
              amount = 1000,
              balanceTransactionAmount = 1000,
              balanceTransactionFeeAmount = 10,
              createdAt = chargeAt,
            ))
          ))
        ),
      )

      val entries = transaction.generateRawJournalEntries()

      NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(chargeAt))) should be(Seq(
        NetAmount(1000, AccountsReceivable),
        NetAmount(990, Cash),
        NetAmount(10, Fees),
        NetAmount(1000, Revenue),
        NetAmount(-1000, UndepositedFunds),
      ))

      NetAmount.compute(entries) should be(Seq(
        NetAmount(990, Cash),
        NetAmount(10, Fees),
        NetAmount(1000, Revenue),
      ))
    }

    it("the charge occurred a month after the invoice payment") {
      val now = Instant.now()
      val paidAt = now.plusSeconds(3600)
      val chargeAt = paidAt.plus(35, ChronoUnit.DAYS)

      val invoice = makeInvoice(
        total = 1000,
        finalizedAt = Some(now),
        paidAt = Some(paidAt),
      )

      val transaction = makeProcessInvoice(
        transaction = makeRevRecTransaction(id = invoice.id, tpe = RevRecTransaction.Type.Invoice),
        invoice = makeRichInvoice(
          base = invoice,
          lineItems = Seq(makeRichInvoiceLineItem(base = makeInvoiceLineItem(
            invoiceId = invoice.id,
            amount = 1000,
            startedAt = Some(now),
            endedAt = Some(now),
          ))),
          payments = Seq(makeRichInvoicePayment(
            base = makeInvoicePayment(invoiceId = invoice.id, paidAt = Some(paidAt)),
            charge = Some(makeRichCharge2(
              amount = 1000,
              balanceTransactionAmount = 1000,
              balanceTransactionFeeAmount = 10,
              createdAt = chargeAt,
            ))
          ))
        ),
      )

      val entries = transaction.generateRawJournalEntries()

      NetAmount.compute(entries, endPeriod = Some(getAccountingPeriod(paidAt))) should be(Seq(
        NetAmount(1000, Revenue),
        NetAmount(1000, UndepositedFunds),
      ))

      NetAmount.compute(entries) should be(Seq(
        NetAmount(990, Cash),
        NetAmount(10, Fees),
        NetAmount(1000, Revenue),
      ))
    }
  }
}

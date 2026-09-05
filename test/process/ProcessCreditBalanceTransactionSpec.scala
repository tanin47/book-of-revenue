package process

import base.Base
import framework.NetAmount
import database.models.*
import database.models.JournalEntry.Account.*
import framework.Instant

class ProcessCreditBalanceTransactionSpec extends Base {
  private[this] val effectiveAt = Instant.parse("2026-01-15T00:00:00Z")

  describe("granting credits") {
    it("books a paid credit grant") {
      val transaction = makeProcessCreditBalanceTransaction(
        transaction = makeRevRecTransaction(),
        creditBalanceTransaction = makeGrantedCreditBalanceTransaction(
          id = "cbtxn_1",
          amount = 100,
          category = "paid",
          effectiveAt = effectiveAt,
        ),
      )

      val entries = transaction.generateRawJournalEntries()

      entries.map(_.event) should be(Seq(JournalEntry.Event.CreateCreditGrant))
      entries.map(_.creditBalanceTransactionId) should be(Seq(Some("cbtxn_1")))
      NetAmount.compute(entries) should be(Seq(
        NetAmount(100, PaidCreditGrantContraAsset),
        NetAmount(100, PaidCreditGrants),
      ))
    }

    it("books a promotional credit grant") {
      val transaction = makeProcessCreditBalanceTransaction(
        transaction = makeRevRecTransaction(),
        creditBalanceTransaction = makeGrantedCreditBalanceTransaction(
          amount = 50,
          category = "promotional",
          effectiveAt = effectiveAt,
        ),
      )

      val entries = transaction.generateRawJournalEntries()

      entries.map(_.event) should be(Seq(JournalEntry.Event.CreateCreditGrant))
      NetAmount.compute(entries) should be(Seq(
        NetAmount(50, PromotionalCreditGrantExpense),
        NetAmount(50, PromotionalCreditGrants),
      ))
    }
  }

  describe("expiring credits") {
    it("reverses a paid credit grant") {
      val transaction = makeProcessCreditBalanceTransaction(
        transaction = makeRevRecTransaction(),
        creditBalanceTransaction = makeExpiredCreditBalanceTransaction(
          amount = 100,
          category = "paid",
          effectiveAt = effectiveAt,
        ),
      )

      val entries = transaction.generateRawJournalEntries()

      entries.map(_.event) should be(Seq(JournalEntry.Event.ExpireCreditGrant))
      NetAmount.compute(entries) should be(Seq(
        NetAmount(-100, PaidCreditGrantContraAsset),
        NetAmount(-100, PaidCreditGrants),
      ))
    }

    it("reverses a promotional credit grant") {
      val transaction = makeProcessCreditBalanceTransaction(
        transaction = makeRevRecTransaction(),
        creditBalanceTransaction = makeExpiredCreditBalanceTransaction(
          amount = 50,
          category = "promotional",
          effectiveAt = effectiveAt,
        ),
      )

      val entries = transaction.generateRawJournalEntries()

      entries.map(_.event) should be(Seq(JournalEntry.Event.ExpireCreditGrant))
      NetAmount.compute(entries) should be(Seq(
        NetAmount(-50, PromotionalCreditGrantExpense),
        NetAmount(-50, PromotionalCreditGrants),
      ))
    }
  }

  describe("voiding credits") {
    it("reverses a voided paid credit grant") {
      val transaction = makeProcessCreditBalanceTransaction(
        transaction = makeRevRecTransaction(),
        creditBalanceTransaction = makeVoidedCreditBalanceTransaction(
          amount = 100,
          category = "paid",
          effectiveAt = effectiveAt,
        ),
      )

      val entries = transaction.generateRawJournalEntries()

      entries.map(_.event) should be(Seq(JournalEntry.Event.VoidCreditGrant))
      NetAmount.compute(entries) should be(Seq(
        NetAmount(-100, PaidCreditGrantContraAsset),
        NetAmount(-100, PaidCreditGrants),
      ))
    }
  }

  describe("delegated transactions") {
    it("skips a transaction returned from a voided invoice") {
      val transaction = makeProcessCreditBalanceTransaction(
        transaction = makeRevRecTransaction(),
        creditBalanceTransaction = makeRichCreditBalanceTransaction(
          base = makeCreditBalanceTransaction(
            `type` = "credit",
            creditAmount = Some(100),
            creditCurrency = Some("usd"),
            creditInvoiceVoidedInvoiceId = Some("in_1"),
            creditInvoiceVoidedInvoiceLineItemId = Some("li_1"),
          ),
        ),
      )

      transaction.generateRawJournalEntries() should be(Seq.empty)
    }

    it("skips a transaction applied to an invoice") {
      val transaction = makeProcessCreditBalanceTransaction(
        transaction = makeRevRecTransaction(),
        creditBalanceTransaction = makeRichCreditBalanceTransaction(
          base = makeCreditBalanceTransaction(
            `type` = "debit",
            debitAmount = Some(100),
            debitCurrency = Some("usd"),
            debitType = Some("credits_applied"),
            debitCreditsAppliedInvoiceId = Some("in_1"),
            debitCreditsAppliedInvoiceLineItemId = Some("li_1"),
          ),
        ),
      )

      transaction.generateRawJournalEntries() should be(Seq.empty)
    }
  }
}

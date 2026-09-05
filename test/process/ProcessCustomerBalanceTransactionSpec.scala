package process

import base.Base
import framework.NetAmount
import database.models.*
import database.models.JournalEntry.Account.*
import framework.Instant

class ProcessCustomerBalanceTransactionSpec extends Base {
  private[this] val createdAt = Instant.parse("2026-01-15T00:00:00Z")

  describe("manual adjustments") {
    it("books a credit to the customer balance") {
      val transaction = makeProcessCustomerBalanceTransaction(
        transaction = makeRevRecTransaction(),
        customerBalanceTransaction = makeCustomerBalanceTransaction(
          id = "cbt_1",
          amount = 1000,
          created = createdAt,
          `type` = "adjustment",
        ),
      )

      val entries = transaction.generateRawJournalEntries()

      entries.map(_.event) should be(Seq(JournalEntry.Event.AdjustCustomerBalanceManually))
      entries.map(_.customerBalanceTransactionId) should be(Seq(Some("cbt_1")))
      NetAmount.compute(entries) should be(Seq(
        NetAmount(-1000, CustomerBalance),
        NetAmount(-1000, CustomerBalanceAdjustment),
      ))
    }

    it("books a debit that reduces the customer balance") {
      val transaction = makeProcessCustomerBalanceTransaction(
        transaction = makeRevRecTransaction(),
        customerBalanceTransaction = makeCustomerBalanceTransaction(
          amount = -500,
          created = createdAt,
          `type` = "adjustment",
        ),
      )

      val entries = transaction.generateRawJournalEntries()

      entries.map(_.event) should be(Seq(JournalEntry.Event.AdjustCustomerBalanceManually))
      NetAmount.compute(entries) should be(Seq(
        NetAmount(500, CustomerBalance),
        NetAmount(500, CustomerBalanceAdjustment),
      ))
    }

    it("settles in the transaction's currency") {
      val transaction = makeProcessCustomerBalanceTransaction(
        transaction = makeRevRecTransaction(),
        customerBalanceTransaction = makeCustomerBalanceTransaction(
          amount = 1000,
          currency = "eur",
          created = createdAt,
          `type` = "adjustment",
        ),
      )

      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(-1000, CustomerBalance, "eur"),
        NetAmount(-1000, CustomerBalanceAdjustment, "eur"),
      ))
    }
  }

  describe("delegated transactions") {
    it("skips a transaction tied to an invoice") {
      val transaction = makeProcessCustomerBalanceTransaction(
        transaction = makeRevRecTransaction(),
        customerBalanceTransaction = makeCustomerBalanceTransaction(
          amount = 1000,
          created = createdAt,
          invoiceId = Some("in_1"),
          `type` = "applied_to_invoice",
        ),
      )

      transaction.generateRawJournalEntries() should be(Seq.empty)
    }

    it("skips a transaction tied to a credit note") {
      val transaction = makeProcessCustomerBalanceTransaction(
        transaction = makeRevRecTransaction(),
        customerBalanceTransaction = makeCustomerBalanceTransaction(
          amount = 1000,
          created = createdAt,
          creditNoteId = Some("cn_1"),
          `type` = "credit_note",
        ),
      )

      transaction.generateRawJournalEntries() should be(Seq.empty)
    }
  }
}

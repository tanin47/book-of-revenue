package process

import base.Base
import framework.NetAmount
import database.models.*
import database.models.JournalEntry.Account.*
import framework.Instant

import java.time.temporal.ChronoUnit

class ProcessStandaloneChargeSpec extends Base {
  it("paid charge") {
    val now = Instant.now()
    val bt = makeBalanceTransaction(
      id = "bt_1",
      amount = 1000,
      netAmount = 1000,
      createdAt = now,
    )

    val charge = makeCharge(
      id = "ch_1",
      amount = 1000,
      balanceTransactionId = Some(bt.id),
      created = now,
    )

    val transaction = makeProcessStandaloneCharge(
      transaction = makeRevRecTransaction(id = charge.id, tpe = RevRecTransaction.Type.StandaloneCharge),
      charge = makeRichCharge(base = charge, balanceTransaction = Some(bt)),
    )

    val amounts = NetAmount.compute(transaction.generateRawJournalEntries())

    amounts should be(Seq(
      NetAmount(1000, Cash),
      NetAmount(1000, Revenue)
    ))
  }

  it("paid charge with fee") {
    val now = Instant.now()
    val bt = makeBalanceTransaction(
      id = "bt_1",
      amount = 1000,
      feeAmount = 10,
      netAmount = 990,
      createdAt = now,
    )

    val charge = makeCharge(
      id = "ch_1",
      amount = 1000,
      balanceTransactionId = Some(bt.id),
      created = now,
    )

    val transaction = makeProcessStandaloneCharge(
      transaction = makeRevRecTransaction(id = charge.id, tpe = RevRecTransaction.Type.StandaloneCharge),
      charge = makeRichCharge(base = charge, balanceTransaction = Some(bt)),
    )

    val amounts = NetAmount.compute(transaction.generateRawJournalEntries())

    amounts should be(Seq(
      NetAmount(990, Cash),
      NetAmount(10, Fees),
      NetAmount(1000, Revenue)
    ))
  }

  it("paid charge settled in a different currency") {
    val now = Instant.now()
    val bt = makeBalanceTransaction(
      id = "bt_1",
      amount = 900,
      currency = "eur",
      feeAmount = 9,
      netAmount = 891,
      createdAt = now,
    )

    val charge = makeCharge(
      id = "ch_1",
      amount = 1000,
      currency = "usd",
      balanceTransactionId = Some(bt.id),
      created = now,
    )

    val transaction = makeProcessStandaloneCharge(
      transaction = makeRevRecTransaction(id = charge.id, tpe = RevRecTransaction.Type.StandaloneCharge),
      charge = makeRichCharge(base = charge, balanceTransaction = Some(bt)),
    )

    val amounts = NetAmount.compute(transaction.generateRawJournalEntries())

    amounts should be(Seq(
      NetAmount(891, Cash, "eur"),
      NetAmount(9, Fees, "eur"),
      NetAmount(900, Revenue, "eur")
    ))
  }

  describe("issues full refund, fails, and issues another full refund") {
    it("refunds with no fx loss") {
      val now = java.time.Instant.parse("2025-01-01T00:00:01Z")

      val charge = makeRichCharge2(
        amount = 1200,
        currency = "usd",
        balanceTransactionAmount = 1100L,
        balanceTransactionCurrency = "eur",
        createdAt = now,
        refunds = Seq(makeRichRefund2(
          amount = 1200,
          currency = "usd",
          balanceTransactionAmount = -1100L,
          balanceTransactionCurrency = "eur",
          createdAt = now.plus(45, ChronoUnit.DAYS),
        ))
      )
      var transaction = makeProcessStandaloneCharge(
        transaction = makeRevRecTransaction(id = charge.base.id, tpe = RevRecTransaction.Type.StandaloneCharge),
        charge = charge,
      )
      val entries = transaction.generateRawJournalEntries()
      NetAmount.compute(entries) should be(Seq(
        NetAmount(1100, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          refunds = Seq(transaction.charge.refunds.head.copy(
            failureBalanceTransaction = Some(makeBalanceTransaction(
              amount = 1100,
              currency = "eur",
              createdAt = now.plus(46, ChronoUnit.DAYS),
            ))
          ))
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(1100, Cash, "eur"),
        NetAmount(1100, Recoverables, "eur"),
        NetAmount(1100, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          refunds = transaction.charge.refunds :+ makeRichRefund2(
            amount = 1200L,
            currency = "usd",
            balanceTransactionAmount = -1100,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(60, ChronoUnit.DAYS),
          )
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(1100, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))
    }

    it("refunds with fx loss") {
      val now = java.time.Instant.parse("2025-01-01T00:00:01Z")

      val charge = makeRichCharge2(
        amount = 1200,
        currency = "usd",
        balanceTransactionAmount = 1100L,
        balanceTransactionCurrency = "eur",
        createdAt = now,
        refunds = Seq(makeRichRefund2(
          amount = 1200,
          currency = "usd",
          balanceTransactionAmount = -1200L,
          balanceTransactionCurrency = "eur",
          createdAt = now.plus(45, ChronoUnit.DAYS)
        ))
      )
      var transaction = makeProcessStandaloneCharge(
        transaction = makeRevRecTransaction(id = charge.base.id, tpe = RevRecTransaction.Type.StandaloneCharge),
        charge = charge,
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(-100, Cash, "eur"),
        NetAmount(100, Loss, "eur"),
        NetAmount(1100, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          refunds = Seq(transaction.charge.refunds.head.copy(
            failureBalanceTransaction = Some(makeBalanceTransaction(
              amount = 1200,
              currency = "eur",
              createdAt = now.plus(46, ChronoUnit.DAYS),
            ))
          ))
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(1100, Cash, "eur"),
        NetAmount(1100, Recoverables, "eur"),
        NetAmount(1100, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          refunds = transaction.charge.refunds :+ makeRichRefund2(
            amount = 1200L,
            currency = "usd",
            balanceTransactionAmount = -1200,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(60, ChronoUnit.DAYS),
          )
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(-100, Cash, "eur"),
        NetAmount(100, Loss, "eur"),
        NetAmount(1100, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))
    }

    it("refunds with fx gain") {
      val now = java.time.Instant.parse("2025-01-01T00:00:01Z")

      val charge = makeRichCharge2(
        amount = 1200,
        currency = "usd",
        balanceTransactionAmount = 1100L,
        balanceTransactionCurrency = "eur",
        createdAt = now,
        refunds = Seq(makeRichRefund2(
          amount = 1200,
          currency = "usd",
          balanceTransactionAmount = -1000L,
          balanceTransactionCurrency = "eur",
          createdAt = now.plus(45, ChronoUnit.DAYS)
        ))
      )
      var transaction = makeProcessStandaloneCharge(
        transaction = makeRevRecTransaction(id = charge.base.id, tpe = RevRecTransaction.Type.StandaloneCharge),
        charge = charge,
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(100, Cash, "eur"),
        NetAmount(-100, Loss, "eur"),
        NetAmount(1100, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          refunds = Seq(transaction.charge.refunds.head.copy(
            failureBalanceTransaction = Some(makeBalanceTransaction(
              amount = 1000,
              currency = "eur",
              createdAt = now.plus(46, ChronoUnit.DAYS),
            ))
          ))
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(1100, Cash, "eur"),
        NetAmount(1100, Recoverables, "eur"),
        NetAmount(1100, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          refunds = transaction.charge.refunds :+ makeRichRefund2(
            amount = 1200L,
            currency = "usd",
            balanceTransactionAmount = -1000,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(60, ChronoUnit.DAYS),
          )
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(100, Cash, "eur"),
        NetAmount(100, Recoverables, "eur"),
        NetAmount(1100, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))
    }
  }

  describe("refunds") {
    it("goes through refunding: having a gain and over-refunding,") {
      val now = java.time.Instant.parse("2025-01-01T00:00:01Z")

      val charge = makeRichCharge2(
        amount = 1200,
        currency = "usd",
        balanceTransactionAmount = 1100L,
        balanceTransactionCurrency = "eur",
        createdAt = now,
        refunds = Seq(makeRichRefund2(
          amount = 600,
          currency = "usd",
          balanceTransactionAmount = -550,
          balanceTransactionCurrency = "eur",
          createdAt = now.plus(45, ChronoUnit.DAYS)
        )),
      )
      var transaction = makeProcessStandaloneCharge(
        transaction = makeRevRecTransaction(id = charge.base.id, tpe = RevRecTransaction.Type.StandaloneCharge),
        charge = charge
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(550, Cash, "eur"),
        NetAmount(550, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          refunds = transaction.charge.refunds :+ makeRichRefund2(
            amount = 600L,
            currency = "usd",
            balanceTransactionAmount = -500,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(46, ChronoUnit.DAYS)
          )
        )
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(50, Cash, "eur"),
        NetAmount(-50, Loss, "eur"),
        NetAmount(1100, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          refunds = transaction.charge.refunds :+ makeRichRefund2(
            amount = 15,
            currency = "usd",
            balanceTransactionAmount = -10,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(47, ChronoUnit.DAYS)
          )
        )
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(40, Cash, "eur"),
        NetAmount(-40, Loss, "eur"),
        NetAmount(1100, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          refunds = transaction.charge.refunds :+ makeRichRefund2(
            amount = 70,
            currency = "usd",
            balanceTransactionAmount = -60,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(48, ChronoUnit.DAYS)
          )
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(-20, Cash, "eur"),
        NetAmount(20, Loss, "eur"),
        NetAmount(1100, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))
    }

    it("refunded charge settled in a different currency, with two partial refunds with fx loss") {
      val now = java.time.Instant.parse("2025-01-01T00:00:01Z")

      val charge = makeRichCharge2(
        amount = 1200,
        currency = "usd",
        balanceTransactionAmount = 1100L,
        balanceTransactionCurrency = "eur",
        createdAt = now,
        refunds = Seq(makeRichRefund2(
          amount = 700,
          currency = "usd",
          balanceTransactionAmount = -710L,
          balanceTransactionCurrency = "eur",
          createdAt = now.plus(45, ChronoUnit.DAYS)
        ))
      )
      var transaction = makeProcessStandaloneCharge(
        transaction = makeRevRecTransaction(id = charge.base.id, tpe = RevRecTransaction.Type.StandaloneCharge),
        charge = charge,
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(390, Cash, "eur"),
        NetAmount(710, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          refunds = transaction.charge.refunds :+ makeRichRefund2(
            amount = 500L,
            currency = "usd",
            balanceTransactionAmount = -400,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(60, ChronoUnit.DAYS),
          )
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(-10, Cash, "eur"),
        NetAmount(10, Loss, "eur"),
        NetAmount(1100, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))
    }

    it("no loss no gain") {
      val now = java.time.Instant.parse("2025-01-01T00:00:01Z")

      val charge = makeRichCharge2(
        amount = 1200,
        currency = "usd",
        balanceTransactionAmount = 1100L,
        balanceTransactionCurrency = "eur",
        createdAt = now,
        refunds = Seq(makeRichRefund2(
          amount = 700,
          currency = "usd",
          balanceTransactionAmount = -710L,
          balanceTransactionCurrency = "eur",
          createdAt = now.plus(45, ChronoUnit.DAYS)
        ))
      )
      var transaction = makeProcessStandaloneCharge(
        transaction = makeRevRecTransaction(id = charge.base.id, tpe = RevRecTransaction.Type.StandaloneCharge),
        charge = charge,
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(390, Cash, "eur"),
        NetAmount(710, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          refunds = transaction.charge.refunds :+ makeRichRefund2(
            amount = 500L,
            currency = "usd",
            balanceTransactionAmount = -390,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(60, ChronoUnit.DAYS),
          )
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(1100, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))
    }

    it("a refund fails then another refund succeeds") {
      val now = java.time.Instant.parse("2025-01-01T00:00:01Z")

      val charge = makeRichCharge2(
        amount = 1200,
        currency = "usd",
        balanceTransactionAmount = 1100L,
        balanceTransactionCurrency = "eur",
        createdAt = now,
        refunds = Seq(makeRichRefund2(
          amount = 700,
          currency = "usd",
          balanceTransactionAmount = -710L,
          balanceTransactionCurrency = "eur",
          createdAt = now.plus(45, ChronoUnit.DAYS),
          failureBalanceTransactionAmount = Some(710),
          failureBalanceTransactionCreatedAt = Some(now.plus(46, ChronoUnit.DAYS)),
        ))
      )
      var transaction = makeProcessStandaloneCharge(
        transaction = makeRevRecTransaction(id = charge.base.id, tpe = RevRecTransaction.Type.StandaloneCharge),
        charge = charge,
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(1100, Cash, "eur"),
        NetAmount(710, Recoverables, "eur"),
        NetAmount(710, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          refunds = transaction.charge.refunds :+ makeRichRefund2(
            amount = 700L,
            currency = "usd",
            balanceTransactionAmount = -720,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(60, ChronoUnit.DAYS),
          )
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(380, Cash, "eur"),
        NetAmount(720, Refunds, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))
    }
  }

  describe("disputes") {
    it("disputes with a dispute fee") {
      val now = java.time.Instant.parse("2025-01-01T00:00:01Z")

      val charge = makeRichCharge2(
        amount = 1200,
        currency = "usd",
        balanceTransactionAmount = 1200L,
        createdAt = now,
        disputes = Seq(makeRichDispute2(
          amount = 1200,
          currency = "usd",
          balanceTransactionAmount = -1200,
          balanceTransactionFee = 1500,
          createdAt = now.plus(45, ChronoUnit.DAYS)
        )),
      )
      var transaction = makeProcessStandaloneCharge(
        transaction = makeRevRecTransaction(id = charge.base.id, tpe = RevRecTransaction.Type.StandaloneCharge),
        charge = charge
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(-1500, Cash),
        NetAmount(1200, Disputes),
        NetAmount(1500, Fees),
        NetAmount(1200, Revenue),
      ))
    }

    it("goes through disputing: having a gain and over-disputing,") {
      val now = java.time.Instant.parse("2025-01-01T00:00:01Z")

      val charge = makeRichCharge2(
        amount = 1200,
        currency = "usd",
        balanceTransactionAmount = 1100L,
        balanceTransactionCurrency = "eur",
        createdAt = now,
        disputes = Seq(makeRichDispute2(
          amount = 600,
          currency = "usd",
          balanceTransactionAmount = -550,
          balanceTransactionCurrency = "eur",
          createdAt = now.plus(45, ChronoUnit.DAYS)
        )),
      )
      var transaction = makeProcessStandaloneCharge(
        transaction = makeRevRecTransaction(id = charge.base.id, tpe = RevRecTransaction.Type.StandaloneCharge),
        charge = charge
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(550, Cash, "eur"),
        NetAmount(550, Disputes, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          disputes = transaction.charge.disputes :+ makeRichDispute2(
            amount = 600L,
            currency = "usd",
            balanceTransactionAmount = -500,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(46, ChronoUnit.DAYS)
          )
        )
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(50, Cash, "eur"),
        NetAmount(1100, Disputes, "eur"),
        NetAmount(-50, Loss, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          disputes = transaction.charge.disputes :+ makeRichDispute2(
            amount = 15,
            currency = "usd",
            balanceTransactionAmount = -10,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(47, ChronoUnit.DAYS)
          )
        )
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(40, Cash, "eur"),
        NetAmount(1100, Disputes, "eur"),
        NetAmount(-40, Loss, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          disputes = transaction.charge.disputes :+ makeRichDispute2(
            amount = 70,
            currency = "usd",
            balanceTransactionAmount = -60,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(48, ChronoUnit.DAYS)
          )
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(-20, Cash, "eur"),
        NetAmount(1100, Disputes, "eur"),
        NetAmount(20, Loss, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))
    }

    it("disputed charge settled in a different currency, with two partial disputes with fx loss") {
      val now = java.time.Instant.parse("2025-01-01T00:00:01Z")

      val charge = makeRichCharge2(
        amount = 1200,
        currency = "usd",
        balanceTransactionAmount = 1100L,
        balanceTransactionCurrency = "eur",
        createdAt = now,
        disputes = Seq(makeRichDispute2(
          amount = 700,
          currency = "usd",
          balanceTransactionAmount = -710L,
          balanceTransactionCurrency = "eur",
          createdAt = now.plus(45, ChronoUnit.DAYS)
        ))
      )
      var transaction = makeProcessStandaloneCharge(
        transaction = makeRevRecTransaction(id = charge.base.id, tpe = RevRecTransaction.Type.StandaloneCharge),
        charge = charge,
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(390, Cash, "eur"),
        NetAmount(710, Disputes, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          disputes = transaction.charge.disputes :+ makeRichDispute2(
            amount = 500L,
            currency = "usd",
            balanceTransactionAmount = -400,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(60, ChronoUnit.DAYS),
          )
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(-10, Cash, "eur"),
        NetAmount(1100, Disputes, "eur"),
        NetAmount(10, Loss, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))
    }

    it("no loss no gain") {
      val now = java.time.Instant.parse("2025-01-01T00:00:01Z")

      val charge = makeRichCharge2(
        amount = 1200,
        currency = "usd",
        balanceTransactionAmount = 1100L,
        balanceTransactionCurrency = "eur",
        createdAt = now,
        disputes = Seq(makeRichDispute2(
          amount = 700,
          currency = "usd",
          balanceTransactionAmount = -710L,
          balanceTransactionCurrency = "eur",
          createdAt = now.plus(45, ChronoUnit.DAYS)
        ))
      )
      var transaction = makeProcessStandaloneCharge(
        transaction = makeRevRecTransaction(id = charge.base.id, tpe = RevRecTransaction.Type.StandaloneCharge),
        charge = charge,
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(390, Cash, "eur"),
        NetAmount(710, Disputes, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          disputes = transaction.charge.disputes :+ makeRichDispute2(
            amount = 500L,
            currency = "usd",
            balanceTransactionAmount = -390,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(60, ChronoUnit.DAYS),
          )
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(1100, Disputes, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))
    }

    it("win disputes then lose another dispute") {
      val now = java.time.Instant.parse("2025-01-01T00:00:01Z")

      val charge = makeRichCharge2(
        amount = 1200,
        currency = "usd",
        balanceTransactionAmount = 1100L,
        balanceTransactionCurrency = "eur",
        createdAt = now,
        disputes = Seq(makeRichDispute2(
          amount = 700,
          currency = "usd",
          balanceTransactionAmount = -710L,
          balanceTransactionCurrency = "eur",
          createdAt = now.plus(45, ChronoUnit.DAYS),
          wonBalanceTransactionAmount = Some(710),
          wonBalanceTransactionCreatedAt = Some(now.plus(46, ChronoUnit.DAYS)),
        ))
      )
      var transaction = makeProcessStandaloneCharge(
        transaction = makeRevRecTransaction(id = charge.base.id, tpe = RevRecTransaction.Type.StandaloneCharge),
        charge = charge,
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(1100, Cash, "eur"),
        NetAmount(710, Disputes, "eur"),
        NetAmount(710, Recoverables, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))

      transaction = transaction.copy(
        charge = transaction.charge.copy(
          disputes = transaction.charge.disputes :+ makeRichDispute2(
            amount = 700L,
            currency = "usd",
            balanceTransactionAmount = -720,
            balanceTransactionCurrency = "eur",
            createdAt = now.plus(60, ChronoUnit.DAYS),
          )
        ),
      )
      NetAmount.compute(transaction.generateRawJournalEntries()) should be(Seq(
        NetAmount(380, Cash, "eur"),
        NetAmount(720, Disputes, "eur"),
        NetAmount(1100, Revenue, "eur"),
      ))
    }
  }
}

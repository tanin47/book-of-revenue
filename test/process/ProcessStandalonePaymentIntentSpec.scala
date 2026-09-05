package process

import base.Base
import framework.NetAmount
import database.models.*
import database.models.JournalEntry.Account.*
import framework.Instant

import java.time.temporal.ChronoUnit

class ProcessStandalonePaymentIntentSpec extends Base {
  it("paid payment intent") {
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
    val pi = makePaymentIntent(id = "pi_1")

    val transaction = makeProcessStandalonePaymentIntent(
      transaction = makeRevRecTransaction(id = pi.id, tpe = RevRecTransaction.Type.StandalonePaymentIntent),
      paymentIntent = makeRichPaymentIntent(base = pi, charge = Some(makeRichCharge(base = charge, balanceTransaction = Some(bt)))),
    )

    val amounts = NetAmount.compute(transaction.generateRawJournalEntries())

    amounts should be(Seq(
      NetAmount(1000, Cash),
      NetAmount(1000, Revenue)
    ))
  }

  it("paid payment intent with fee") {
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
    val pi = makePaymentIntent(id = "pi_1")

    val transaction = makeProcessStandalonePaymentIntent(
      transaction = makeRevRecTransaction(id = pi.id, tpe = RevRecTransaction.Type.StandalonePaymentIntent),
      paymentIntent = makeRichPaymentIntent(base = pi, charge = Some(makeRichCharge(base = charge, balanceTransaction = Some(bt)))),
    )

    val amounts = NetAmount.compute(transaction.generateRawJournalEntries())

    amounts should be(Seq(
      NetAmount(990, Cash),
      NetAmount(10, Fees),
      NetAmount(1000, Revenue)
    ))
  }

  it("paid payment intent settled in a different currency") {
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
    val pi = makePaymentIntent(id = "pi_1")

    val transaction = makeProcessStandalonePaymentIntent(
      transaction = makeRevRecTransaction(id = pi.id, tpe = RevRecTransaction.Type.StandalonePaymentIntent),
      paymentIntent = makeRichPaymentIntent(base = pi, charge = Some(makeRichCharge(base = charge, balanceTransaction = Some(bt)))),
    )

    val amounts = NetAmount.compute(transaction.generateRawJournalEntries())

    amounts should be(Seq(
      NetAmount(891, Cash, "eur"),
      NetAmount(9, Fees, "eur"),
      NetAmount(900, Revenue, "eur")
    ))
  }

  it("refunded payment intent settled in a different currency, with a failed refund and a re-refund") {
    val now = java.time.Instant.parse("2025-01-01T00:00:01Z")

    // The charge is presented in usd but settled in eur. usd and eur are at par at
    // charge time, so 1200 usd settles as 1200 eur received.
    val chargeBt = makeBalanceTransaction(
      id = "bt_1",
      amount = 1200,
      currency = "eur",
      netAmount = 1200,
      createdAt = now,
    )

    val charge = makeCharge(
      id = "ch_1",
      amount = 1200,
      currency = "usd",
      balanceTransactionId = Some(chargeBt.id),
      created = now,
    )
    val pi = makePaymentIntent(id = "pi_1")

    // The customer requests a full refund of 1200 usd, but by now eur has strengthened,
    // so returning 1200 usd costs 1300 eur going out -- 100 eur more than we received.
    // That 100 eur excess is booked as an fx loss.
    val refundBt = makeBalanceTransaction(
      id = "bt_2",
      amount = -1300,
      currency = "eur",
      netAmount = -1300,
      `type` = "refund",
      createdAt = now.plus(45, ChronoUnit.DAYS),
    )

    val refund = makeRefund(
      id = "ref_1",
      amount = 1200,
      currency = "usd",
      balanceTransactionId = Some(refundBt.id),
      createdAt = now.plus(45, ChronoUnit.DAYS),
    )

    // Stage 1: only the refund has settled so far. We paid out 1300 eur against 1200 eur
    // received, leaving Cash at -100 eur and a 100 eur fx loss.
    val afterRefund = makeProcessStandalonePaymentIntent(
      transaction = makeRevRecTransaction(id = pi.id, tpe = RevRecTransaction.Type.StandalonePaymentIntent),
      paymentIntent = makeRichPaymentIntent(
        base = pi,
        charge = Some(makeRichCharge(
          base = charge,
          balanceTransaction = Some(chargeBt),
          refunds = Seq(makeRichRefund(refund, balanceTransaction = Some(refundBt)))
        ))
      ),
    )

    NetAmount.compute(afterRefund.generateRawJournalEntries()) should be(Seq(
      NetAmount(-100, Cash, "eur"),
      NetAmount(100, Loss, "eur"),
      NetAmount(1200, Refunds, "eur"),
      NetAmount(1200, Revenue, "eur"),
    ))

    // Stage 2: the refund fails and the 1300 eur comes back to our balance. The fx loss
    // is reversed and the recovered amount is parked in Recoverables.
    val refundFailureBt = makeBalanceTransaction(
      id = "bt_3",
      amount = 1300,
      currency = "eur",
      netAmount = 1300,
      `type` = "refund",
      createdAt = now.plus(60, ChronoUnit.DAYS),
    )

    val afterFailure = makeProcessStandalonePaymentIntent(
      transaction = makeRevRecTransaction(id = pi.id, tpe = RevRecTransaction.Type.StandalonePaymentIntent),
      paymentIntent = makeRichPaymentIntent(
        base = pi,
        charge = Some(makeRichCharge(
          base = charge,
          balanceTransaction = Some(chargeBt),
          refunds = Seq(makeRichRefund(
            refund.copy(failureBalanceTransactionId = Some(refundFailureBt.id)),
            balanceTransaction = Some(refundBt),
            failureBalanceTransaction = Some(refundFailureBt),
          ))
        ))
      ),
    )

    NetAmount.compute(afterFailure.generateRawJournalEntries()) should be(Seq(
      NetAmount(1200, Cash, "eur"),
      NetAmount(1200, Recoverables, "eur"),
      NetAmount(1200, Refunds, "eur"),
      NetAmount(1200, Revenue, "eur"),
    ))

    // Stage 3: a second full refund of 1200 usd settles as 1300 eur going out again, at the
    // same unfavorable rate. It consumes the recoverable and re-books the 100 eur fx loss.
    val refund2Bt = makeBalanceTransaction(
      id = "bt_4",
      amount = -1300,
      currency = "eur",
      netAmount = -1300,
      `type` = "refund",
      createdAt = now.plus(75, ChronoUnit.DAYS),
    )

    val refund2 = makeRefund(
      id = "ref_2",
      amount = 1200,
      currency = "usd",
      balanceTransactionId = Some(refund2Bt.id),
      createdAt = now.plus(75, ChronoUnit.DAYS),
    )

    val afterReRefund = makeProcessStandalonePaymentIntent(
      transaction = makeRevRecTransaction(id = pi.id, tpe = RevRecTransaction.Type.StandalonePaymentIntent),
      paymentIntent = makeRichPaymentIntent(
        base = pi,
        charge = Some(makeRichCharge(
          base = charge,
          balanceTransaction = Some(chargeBt),
          refunds = Seq(
            makeRichRefund(
              refund.copy(failureBalanceTransactionId = Some(refundFailureBt.id)),
              balanceTransaction = Some(refundBt),
              failureBalanceTransaction = Some(refundFailureBt),
            ),
            makeRichRefund(refund2, balanceTransaction = Some(refund2Bt)),
          )
        ))
      ),
    )

    NetAmount.compute(afterReRefund.generateRawJournalEntries()) should be(Seq(
      NetAmount(-100, Cash, "eur"),
      NetAmount(100, Loss, "eur"),
      NetAmount(1200, Refunds, "eur"),
      NetAmount(1200, Revenue, "eur"),
    ))
  }

  it("disputed payment intent settled in a different currency, with a won dispute and another dispute") {
    val now = java.time.Instant.parse("2025-01-01T00:00:01Z")

    // The charge is presented in usd but settled in eur. usd and eur are at par at
    // charge time, so 1200 usd settles as 1200 eur received.
    val chargeBt = makeBalanceTransaction(
      id = "bt_1",
      amount = 1200,
      currency = "eur",
      netAmount = 1200,
      createdAt = now,
    )

    val charge = makeCharge(
      id = "ch_1",
      amount = 1200,
      currency = "usd",
      balanceTransactionId = Some(chargeBt.id),
      created = now,
    )
    val pi = makePaymentIntent(id = "pi_1")

    // The customer disputes the full 1200 usd, but by now eur has strengthened, so returning
    // 1200 usd costs 1300 eur going out -- 100 eur more than we received. That 100 eur excess
    // is booked as an fx loss.
    val disputeBt = makeBalanceTransaction(
      id = "bt_2",
      amount = -1300,
      currency = "eur",
      netAmount = -1300,
      `type` = "adjustment",
      createdAt = now.plus(45, ChronoUnit.DAYS),
    )

    val dispute = makeDispute(
      id = "dis_1",
      amount = 1200,
      currency = "usd",
      balanceTransactionIds = Seq(disputeBt.id),
      createdAt = now.plus(45, ChronoUnit.DAYS),
    )

    // Stage 1: the dispute has only taken funds so far. We paid out 1300 eur against 1200 eur
    // received, leaving Cash at -100 eur and a 100 eur fx loss.
    val afterDispute = makeProcessStandalonePaymentIntent(
      transaction = makeRevRecTransaction(id = pi.id, tpe = RevRecTransaction.Type.StandalonePaymentIntent),
      paymentIntent = makeRichPaymentIntent(
        base = pi,
        charge = Some(makeRichCharge(
          base = charge,
          balanceTransaction = Some(chargeBt),
          disputes = Seq(makeRichDispute(dispute, balanceTransactions = Seq(disputeBt)))
        ))
      ),
    )

    NetAmount.compute(afterDispute.generateRawJournalEntries()) should be(Seq(
      NetAmount(-100, Cash, "eur"),
      NetAmount(1200, Disputes, "eur"),
      NetAmount(100, Loss, "eur"),
      NetAmount(1200, Revenue, "eur"),
    ))

    // Stage 2: we win the dispute and the 1300 eur comes back to our balance. The fx loss is
    // reversed and the recovered amount is parked in Recoverables.
    val wonBt = makeBalanceTransaction(
      id = "bt_3",
      amount = 1300,
      currency = "eur",
      netAmount = 1300,
      `type` = "adjustment",
      createdAt = now.plus(60, ChronoUnit.DAYS),
    )

    val afterWon = makeProcessStandalonePaymentIntent(
      transaction = makeRevRecTransaction(id = pi.id, tpe = RevRecTransaction.Type.StandalonePaymentIntent),
      paymentIntent = makeRichPaymentIntent(
        base = pi,
        charge = Some(makeRichCharge(
          base = charge,
          balanceTransaction = Some(chargeBt),
          disputes = Seq(makeRichDispute(
            dispute.copy(balanceTransactionIds = List(disputeBt.id, wonBt.id)),
            balanceTransactions = Seq(disputeBt, wonBt),
          ))
        ))
      ),
    )

    NetAmount.compute(afterWon.generateRawJournalEntries()) should be(Seq(
      NetAmount(1200, Cash, "eur"),
      NetAmount(1200, Disputes, "eur"),
      NetAmount(1200, Recoverables, "eur"),
      NetAmount(1200, Revenue, "eur"),
    ))

    // Stage 3: the customer disputes again for the full 1200 usd, taking 1300 eur out again at
    // the same unfavorable rate. It consumes the recoverable and re-books the 100 eur fx loss.
    val dispute2Bt = makeBalanceTransaction(
      id = "bt_4",
      amount = -1300,
      currency = "eur",
      netAmount = -1300,
      `type` = "adjustment",
      createdAt = now.plus(75, ChronoUnit.DAYS),
    )

    val dispute2 = makeDispute(
      id = "dis_2",
      amount = 1200,
      currency = "usd",
      balanceTransactionIds = Seq(dispute2Bt.id),
      createdAt = now.plus(75, ChronoUnit.DAYS),
    )

    val afterReDispute = makeProcessStandalonePaymentIntent(
      transaction = makeRevRecTransaction(id = pi.id, tpe = RevRecTransaction.Type.StandalonePaymentIntent),
      paymentIntent = makeRichPaymentIntent(
        base = pi,
        charge = Some(makeRichCharge(
          base = charge,
          balanceTransaction = Some(chargeBt),
          disputes = Seq(
            makeRichDispute(
              dispute.copy(balanceTransactionIds = List(disputeBt.id, wonBt.id)),
              balanceTransactions = Seq(disputeBt, wonBt),
            ),
            makeRichDispute(dispute2, balanceTransactions = Seq(dispute2Bt)),
          )
        ))
      ),
    )

    NetAmount.compute(afterReDispute.generateRawJournalEntries()) should be(Seq(
      NetAmount(-100, Cash, "eur"),
      NetAmount(1200, Disputes, "eur"),
      NetAmount(100, Loss, "eur"),
      NetAmount(1200, Revenue, "eur"),
    ))
  }

  it("payment intent with no charge") {
    val pi = makePaymentIntent(id = "pi_1")

    val transaction = makeProcessStandalonePaymentIntent(
      transaction = makeRevRecTransaction(id = pi.id, tpe = RevRecTransaction.Type.StandalonePaymentIntent),
      paymentIntent = makeRichPaymentIntent(base = pi, charge = None),
    )

    val entries = transaction.generateRawJournalEntries()

    entries should be(Seq.empty)
  }
}

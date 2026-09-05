package framework

import database.models.JournalEntry

object NetAmount {
  def compute(
    entries: Seq[JournalEntry],
    lineItemId: Option[String] = None,
    endPeriod: Option[Instant] = None,
    creditNoteId: Option[String] = None,
    creditNoteLineItemId: Option[String] = None,
    refundId: Option[String] = None,
    principleAccount: Option[JournalEntry.Account] = None
  ): Seq[NetAmount] = {
    val amounts = entries
      .filter { entry =>
        endPeriod.forall(_.toEpochMilli >= entry.accountingPeriod.toEpochMilli) &&
          lineItemId.forall(entry.invoiceLineItemId.contains) &&
          creditNoteId.forall(entry.creditNoteId.contains) &&
          creditNoteLineItemId.forall(entry.creditNoteLineItemId.contains) &&
          refundId.forall(entry.refundId.contains) &&
          principleAccount.forall(entry.principleAccount == _)
      }
      .flatMap { entry =>
        val netAmountForDebit = if (entry.debit.isCredit()) { -entry.settlementAmount } else { entry.settlementAmount }
        val netAmountForCredit = if (entry.credit.isCredit()) { entry.settlementAmount } else { -entry.settlementAmount }
        Seq(
          NetAmount(netAmountForDebit, entry.debit, entry.settlementCurrency),
          NetAmount(netAmountForCredit, entry.credit, entry.settlementCurrency)
        )
      }

    amounts
      .groupBy(_.account)
      .map { case (account, amounts) => NetAmount(amounts.map(_.amount).sum, account, amounts.head.currency) }
      .filter(_.amount != 0)
      .toList
      .sortBy(_.account.name())
  }
}

case class NetAmount(
  amount: Long,
  account: JournalEntry.Account,
  currency: String = "usd",
)

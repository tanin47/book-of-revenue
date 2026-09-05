package process

import database.models.{RevRecTransaction, JournalEntry}
import framework.Instant

abstract class ProcessRevRecTransaction {
  def transaction: RevRecTransaction
  def generateRawJournalEntries(): Seq[JournalEntry]
  def startedAt: Option[Instant]
  def status: RevRecTransaction.Status
  def syncedAt: Instant

  def generateJournalEntries(): Seq[JournalEntry] = {
    generateRawJournalEntries()
      .filter { j => j.settlementAmount != 0 || j.presentmentAmount != 0 }
      .map { j =>
        if (j.settlementAmount < 0 || j.presentmentAmount < 0) {
          j.copy(debit = j.credit, credit = j.debit, settlementAmount = -j.settlementAmount, presentmentAmount = -j.presentmentAmount)
        } else {
          j
        }
      }
      .sortBy(_.occurredAt)
  }
}

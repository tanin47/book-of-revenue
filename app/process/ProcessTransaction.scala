package process

import database.models.{Transaction, JournalEntry}
import framework.Instant

abstract class ProcessTransaction {
  def transaction: Transaction
  def generateRawJournalEntries(): Seq[JournalEntry]
  def startedAt: Option[Instant]
  def status: Transaction.Status
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

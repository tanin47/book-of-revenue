package background

import base.Base
import database.models.Transaction
import database.models.metronome.MetronomeInvoice.BillingProviderType
import framework.Instant

class ProcessTransactionWorkerSpec extends Base {
  lazy val processTransactionWorker: ProcessTransactionWorker = app.injector.instanceOf[ProcessTransactionWorker]

  it("creates the unbilled draft invoice transaction source") {
    val draft = makeMetronomeDraftInvoice()
    processTransactionWorker.run(ProcessTransactionWorkerRequest())

    val ts = await(transactionService.getAll(stripeAccount.id, true, 0, 100))
    ts.size should be(1)

    val t = ts(0)
    t.id should be(draft.id)
    t.tpe should be(Transaction.Type.UnbilledMetronomeDraftInvoice)
  }

  it("creates the invoice transaction source with metronome") {
    val invoice = makeInvoice(finalizedAt = Some(Instant.now()))
    val _ = makeInvoiceLineItem(invoiceId = invoice.id)
    val metronomeDraftInvoice = makeMetronomeDraftInvoice()
    val metronomeInvoice = makeMetronomeInvoice(
      id = metronomeDraftInvoice.id,
      billingProviderInvoiceId = Some(invoice.id),
      billingProviderType = Some(BillingProviderType.STRIPE.toString)
    )

    processTransactionWorker.run(ProcessTransactionWorkerRequest())

    val ts = await(transactionService.getAll(stripeAccount.id, true, 0, 100))
    ts.size should be(1)

    val t = ts(0)
    t.id should be(invoice.id)
    t.tpe should be(Transaction.Type.Invoice)
  }
}

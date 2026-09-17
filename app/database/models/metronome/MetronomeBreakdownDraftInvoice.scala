package database.models.metronome

import framework.Instant
import framework.PostgresProfile.api.*
import slick.collection.heterogeneous.HNil
import slick.lifted.{ProvenShape, Rep}

case class MetronomeBreakdownDraftInvoice(
  id: String,
  environmentType: Option[String],
  snapshotTimestamp: Option[Instant],
  invoiceId: Option[String],
  customerId: Option[String],
  transferId: Option[String],
  creditTypeId: Option[String],
  netPaymentTermDays: Option[Int],
  creditTypeName: Option[String],
  subtotal: Option[BigDecimal],
  total: Option[BigDecimal],
  tpe: Option[String],
  externalInvoice: Option[String],
  planId: Option[String],
  contractId: Option[String],
  amendmentId: Option[String],
  customFields: Option[String],
  billableStatus: Option[String],
  windowSize: Option[String],
  metadata: Option[String],
  issuedAt: Option[Instant],
  invoiceStartTimestamp: Option[Instant],
  invoiceEndTimestamp: Option[Instant],
  breakdownStartTimestamp: Option[Instant],
  breakdownEndTimestamp: Option[Instant],
  updatedAt: Option[Instant]
)

case class RichMetronomeBreakdownDraftInvoice(
  base: MetronomeBreakdownDraftInvoice,
  lineItems: Seq[MetronomeBreakdownDraftLineItem],
)

class MetronomeBreakdownDraftInvoiceTable(tag: Tag) extends Table[MetronomeBreakdownDraftInvoice](tag, Some("metronome"), "breakdowns_draft_invoices") {
  def id: Rep[String] = column[String]("id")
  def environmentType: Rep[Option[String]] = column[Option[String]]("environment_type")
  def snapshotTimestamp: Rep[Option[Instant]] = column[Option[Instant]]("snapshot_timestamp")
  def invoiceId: Rep[Option[String]] = column[Option[String]]("invoice_id")
  def customerId: Rep[Option[String]] = column[Option[String]]("customer_id")
  def transferId: Rep[Option[String]] = column[Option[String]]("transfer_id")
  def creditTypeId: Rep[Option[String]] = column[Option[String]]("credit_type_id")
  def netPaymentTermDays: Rep[Option[Int]] = column[Option[Int]]("net_payment_term_days")
  def creditTypeName: Rep[Option[String]] = column[Option[String]]("credit_type_name")
  def subtotal: Rep[Option[BigDecimal]] = column[Option[BigDecimal]]("subtotal")
  def total: Rep[Option[BigDecimal]] = column[Option[BigDecimal]]("total")
  def tpe: Rep[Option[String]] = column[Option[String]]("type")
  def externalInvoice: Rep[Option[String]] = column[Option[String]]("external_invoice")
  def planId: Rep[Option[String]] = column[Option[String]]("plan_id")
  def contractId: Rep[Option[String]] = column[Option[String]]("contract_id")
  def amendmentId: Rep[Option[String]] = column[Option[String]]("amendment_id")
  def customFields: Rep[Option[String]] = column[Option[String]]("custom_fields")
  def billableStatus: Rep[Option[String]] = column[Option[String]]("billable_status")
  def windowSize: Rep[Option[String]] = column[Option[String]]("window_size")
  def metadata: Rep[Option[String]] = column[Option[String]]("metadata")
  def issuedAt: Rep[Option[Instant]] = column[Option[Instant]]("issued_at")
  def invoiceStartTimestamp: Rep[Option[Instant]] = column[Option[Instant]]("invoice_start_timestamp")
  def invoiceEndTimestamp: Rep[Option[Instant]] = column[Option[Instant]]("invoice_end_timestamp")
  def breakdownStartTimestamp: Rep[Option[Instant]] = column[Option[Instant]]("breakdown_start_timestamp")
  def breakdownEndTimestamp: Rep[Option[Instant]] = column[Option[Instant]]("breakdown_end_timestamp")
  def updatedAt: Rep[Option[Instant]] = column[Option[Instant]]("updated_at")

  def * : ProvenShape[MetronomeBreakdownDraftInvoice] = (
    id ::
    environmentType ::
    snapshotTimestamp ::
    invoiceId ::
    customerId ::
    transferId ::
    creditTypeId ::
    netPaymentTermDays ::
    creditTypeName ::
    subtotal ::
    total ::
    tpe ::
    externalInvoice ::
    planId ::
    contractId ::
    amendmentId ::
    customFields ::
    billableStatus ::
    windowSize ::
    metadata ::
    issuedAt ::
    invoiceStartTimestamp ::
    invoiceEndTimestamp ::
    breakdownStartTimestamp ::
    breakdownEndTimestamp ::
    updatedAt ::
      HNil
  ).mapTo[MetronomeBreakdownDraftInvoice]
}

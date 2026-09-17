package database.models.metronome

import framework.Instant
import framework.PostgresProfile.api.*
import slick.lifted.{ProvenShape, Rep}

case class MetronomeDraftInvoice(
  metronomeMetadataId: Option[String],
  id: String,
  environmentType: Option[String],
  snapshotTime: Option[Instant],
  status: Option[String],
  total: Option[BigDecimal],
  creditTypeId: Option[String],
  creditTypeName: Option[String],
  customerId: Option[String],
  planId: Option[String],
  planName: Option[String],
  contractId: Option[String],
  billableStatus: Option[String],
  billingProviderInvoiceId: Option[String],
  billingProviderInvoiceCreatedAt: Option[Instant],
  label: Option[String],
  startTimestamp: Option[Instant],
  endTimestamp: Option[Instant],
  updatedAt: Option[Instant]
) {
  // Sometimes creditTypeName is "USD" or "USD (cents)"
  lazy val currency: String = creditTypeName.get.split(" ").head.toLowerCase
}

case class RichMetronomeDraftInvoice(
  base: MetronomeDraftInvoice,
  lineItems: Seq[MetronomeDraftLineItem],
  breakdownInvoice: Option[RichMetronomeBreakdownDraftInvoice],
)

class MetronomeDraftInvoiceTable(tag: Tag) extends Table[MetronomeDraftInvoice](tag, Some("metronome"), "draft_invoice") {
  def metronomeMetadataId: Rep[Option[String]] = column[Option[String]]("_metronome_metadata_id")
  def id: Rep[String] = column[String]("id")
  def environmentType: Rep[Option[String]] = column[Option[String]]("environment_type")
  def snapshotTime: Rep[Option[Instant]] = column[Option[Instant]]("snapshot_time")
  def status: Rep[Option[String]] = column[Option[String]]("status")
  def total: Rep[Option[BigDecimal]] = column[Option[BigDecimal]]("total")
  def creditTypeId: Rep[Option[String]] = column[Option[String]]("credit_type_id")
  def creditTypeName: Rep[Option[String]] = column[Option[String]]("credit_type_name")
  def customerId: Rep[Option[String]] = column[Option[String]]("customer_id")
  def planId: Rep[Option[String]] = column[Option[String]]("plan_id")
  def planName: Rep[Option[String]] = column[Option[String]]("plan_name")
  def contractId: Rep[Option[String]] = column[Option[String]]("contract_id")
  def billableStatus: Rep[Option[String]] = column[Option[String]]("billable_status")
  def billingProviderInvoiceId: Rep[Option[String]] = column[Option[String]]("billing_provider_invoice_id")
  def billingProviderInvoiceCreatedAt: Rep[Option[Instant]] = column[Option[Instant]]("billing_provider_invoice_created_at")
  def label: Rep[Option[String]] = column[Option[String]]("label")
  def startTimestamp: Rep[Option[Instant]] = column[Option[Instant]]("start_timestamp")
  def endTimestamp: Rep[Option[Instant]] = column[Option[Instant]]("end_timestamp")
  def updatedAt: Rep[Option[Instant]] = column[Option[Instant]]("updated_at")

  def * : ProvenShape[MetronomeDraftInvoice] = (
    metronomeMetadataId,
    id,
    environmentType,
    snapshotTime,
    status,
    total,
    creditTypeId,
    creditTypeName,
    customerId,
    planId,
    planName,
    contractId,
    billableStatus,
    billingProviderInvoiceId,
    billingProviderInvoiceCreatedAt,
    label,
    startTimestamp,
    endTimestamp,
    updatedAt
  ).<>((MetronomeDraftInvoice.apply _).tupled, MetronomeDraftInvoice.unapply)
}

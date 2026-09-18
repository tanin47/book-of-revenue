package database.models.metronome

import framework.Instant
import framework.PostgresProfile.api.*
import slick.lifted.{ProvenShape, Rep}

object MetronomeInvoice {
  enum BillingProviderType extends Enum[BillingProviderType] {
    case STRIPE
  }
}

case class MetronomeInvoice(
  id: String,
  environmentType: Option[String],
  status: Option[String],
  total: Option[BigDecimal],
  creditTypeId: Option[String],
  creditTypeName: Option[String],
  customerId: Option[String],
  planId: Option[String],
  planName: Option[String],
  contractId: Option[String],
  billingProviderInvoiceId: Option[String],
  billingProviderType: Option[String],
  billingProviderInvoiceCreatedAt: Option[Instant],
  billingProviderInvoiceExternalStatus: Option[String],
  invoiceLabel: Option[String],
  metadata: Option[String],
  startTimestamp: Option[Instant],
  endTimestamp: Option[Instant],
  issuedAt: Option[Instant],
  updatedAt: Option[Instant]
)

case class RichMetronomeInvoice(
  base: MetronomeInvoice,
  lineItems: Seq[MetronomeLineItem],
  breakdownInvoice: Option[RichMetronomeBreakdownInvoice],
)

class MetronomeInvoiceTable(tag: Tag) extends Table[MetronomeInvoice](tag, Some("metronome"), "invoice") {
  def id: Rep[String] = column[String]("id")
  def environmentType: Rep[Option[String]] = column[Option[String]]("environment_type")
  def status: Rep[Option[String]] = column[Option[String]]("status")
  def total: Rep[Option[BigDecimal]] = column[Option[BigDecimal]]("total")
  def creditTypeId: Rep[Option[String]] = column[Option[String]]("credit_type_id")
  def creditTypeName: Rep[Option[String]] = column[Option[String]]("credit_type_name")
  def customerId: Rep[Option[String]] = column[Option[String]]("customer_id")
  def planId: Rep[Option[String]] = column[Option[String]]("plan_id")
  def planName: Rep[Option[String]] = column[Option[String]]("plan_name")
  def contractId: Rep[Option[String]] = column[Option[String]]("contract_id")
  def billingProviderInvoiceId: Rep[Option[String]] = column[Option[String]]("billing_provider_invoice_id")
  def billingProviderType: Rep[Option[String]] = column[Option[String]]("billing_provider_type")
  def billingProviderInvoiceCreatedAt: Rep[Option[Instant]] = column[Option[Instant]]("billing_provider_invoice_created_at")
  def billingProviderInvoiceExternalStatus: Rep[Option[String]] = column[Option[String]]("billing_provider_invoice_external_status")
  def invoiceLabel: Rep[Option[String]] = column[Option[String]]("invoice_label")
  def metadata: Rep[Option[String]] = column[Option[String]]("metadata")
  def startTimestamp: Rep[Option[Instant]] = column[Option[Instant]]("start_timestamp")
  def endTimestamp: Rep[Option[Instant]] = column[Option[Instant]]("end_timestamp")
  def issuedAt: Rep[Option[Instant]] = column[Option[Instant]]("issued_at")
  def updatedAt: Rep[Option[Instant]] = column[Option[Instant]]("updated_at")

  def * : ProvenShape[MetronomeInvoice] = (
    id,
    environmentType,
    status,
    total,
    creditTypeId,
    creditTypeName,
    customerId,
    planId,
    planName,
    contractId,
    billingProviderInvoiceId,
    billingProviderType,
    billingProviderInvoiceCreatedAt,
    billingProviderInvoiceExternalStatus,
    invoiceLabel,
    metadata,
    startTimestamp,
    endTimestamp,
    issuedAt,
    updatedAt
  ).<>((MetronomeInvoice.apply _).tupled, MetronomeInvoice.unapply)
}

package database.models.metronome

import framework.Instant
import framework.PostgresProfile.api.*
import slick.collection.heterogeneous.HNil
import slick.lifted.{ProvenShape, Rep}

case class MetronomeBreakdownDraftLineItem(
  id: String,
  environmentType: Option[String],
  snapshotTimestamp: Option[Instant],
  watermarkTimestamp: Option[Instant],
  invoiceBreakdownId: Option[String],
  name: Option[String],
  transferId: Option[String],
  groupKey: Option[String],
  groupValue: Option[String],
  quantity: Option[BigDecimal],
  total: Option[BigDecimal],
  unitPrice: Option[BigDecimal],
  productId: Option[String],
  productType: Option[String],
  creditTypeId: Option[String],
  creditTypeName: Option[String],
  commitId: Option[String],
  commitSegmentId: Option[String],
  commitType: Option[String],
  subscriptionId: Option[String],
  isProrated: Option[Boolean],
  lineItemId: Option[String],
  lineItemType: Option[String],
  customFields: Option[String],
  pricingGroupValues: Option[String],
  presentationGroupValues: Option[String],
  billableMetricId: Option[String],
  metadata: Option[String],
  breakdownStartTimestamp: Option[Instant],
  breakdownEndTimestamp: Option[Instant],
  updatedAt: Option[Instant]
) {
  lazy val isAppliedCredit: Boolean = lineItemType.contains("applied_commit_or_credit")
}

class MetronomeBreakdownDraftLineItemTable(tag: Tag) extends Table[MetronomeBreakdownDraftLineItem](tag, Some("metronome"), "breakdowns_draft_line_items") {
  def id: Rep[String] = column[String]("id")
  def environmentType: Rep[Option[String]] = column[Option[String]]("environment_type")
  def snapshotTimestamp: Rep[Option[Instant]] = column[Option[Instant]]("snapshot_timestamp")
  def watermarkTimestamp: Rep[Option[Instant]] = column[Option[Instant]]("watermark_timestamp")
  def invoiceBreakdownId: Rep[Option[String]] = column[Option[String]]("invoice_breakdown_id")
  def name: Rep[Option[String]] = column[Option[String]]("name")
  def transferId: Rep[Option[String]] = column[Option[String]]("transfer_id")
  def groupKey: Rep[Option[String]] = column[Option[String]]("group_key")
  def groupValue: Rep[Option[String]] = column[Option[String]]("group_value")
  def quantity: Rep[Option[BigDecimal]] = column[Option[BigDecimal]]("quantity")
  def total: Rep[Option[BigDecimal]] = column[Option[BigDecimal]]("total")
  def unitPrice: Rep[Option[BigDecimal]] = column[Option[BigDecimal]]("unit_price")
  def productId: Rep[Option[String]] = column[Option[String]]("product_id")
  def productType: Rep[Option[String]] = column[Option[String]]("product_type")
  def creditTypeId: Rep[Option[String]] = column[Option[String]]("credit_type_id")
  def creditTypeName: Rep[Option[String]] = column[Option[String]]("credit_type_name")
  def commitId: Rep[Option[String]] = column[Option[String]]("commit_id")
  def commitSegmentId: Rep[Option[String]] = column[Option[String]]("commit_segment_id")
  def commitType: Rep[Option[String]] = column[Option[String]]("commit_type")
  def subscriptionId: Rep[Option[String]] = column[Option[String]]("subscription_id")
  def isProrated: Rep[Option[Boolean]] = column[Option[Boolean]]("is_prorated")
  def lineItemId: Rep[Option[String]] = column[Option[String]]("line_item_id")
  def lineItemType: Rep[Option[String]] = column[Option[String]]("line_item_type")
  def customFields: Rep[Option[String]] = column[Option[String]]("custom_fields")
  def pricingGroupValues: Rep[Option[String]] = column[Option[String]]("pricing_group_values")
  def presentationGroupValues: Rep[Option[String]] = column[Option[String]]("presentation_group_values")
  def billableMetricId: Rep[Option[String]] = column[Option[String]]("billable_metric_id")
  def metadata: Rep[Option[String]] = column[Option[String]]("metadata")
  def breakdownStartTimestamp: Rep[Option[Instant]] = column[Option[Instant]]("breakdown_start_timestamp")
  def breakdownEndTimestamp: Rep[Option[Instant]] = column[Option[Instant]]("breakdown_end_timestamp")
  def updatedAt: Rep[Option[Instant]] = column[Option[Instant]]("updated_at")

  def * : ProvenShape[MetronomeBreakdownDraftLineItem] = (
    id ::
    environmentType ::
    snapshotTimestamp ::
    watermarkTimestamp ::
    invoiceBreakdownId ::
    name ::
    transferId ::
    groupKey ::
    groupValue ::
    quantity ::
    total ::
    unitPrice ::
    productId ::
    productType ::
    creditTypeId ::
    creditTypeName ::
    commitId ::
    commitSegmentId ::
    commitType ::
    subscriptionId ::
    isProrated ::
    lineItemId ::
    lineItemType ::
    customFields ::
    pricingGroupValues ::
    presentationGroupValues ::
    billableMetricId ::
    metadata ::
    breakdownStartTimestamp ::
    breakdownEndTimestamp ::
    updatedAt ::
      HNil
  ).mapTo[MetronomeBreakdownDraftLineItem]
}

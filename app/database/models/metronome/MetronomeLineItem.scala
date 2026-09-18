package database.models.metronome

import framework.Instant
import framework.PostgresProfile.api.*
import play.api.libs.json.Json
import slick.lifted.{ProvenShape, Rep}

case class MetronomeLineItem(
  id: String,
  environmentType: Option[String],
  invoiceId: Option[String],
  creditGrantId: Option[String],
  creditTypeId: Option[String],
  creditTypeName: Option[String],
  name: Option[String],
  quantity: Option[BigDecimal],
  total: Option[BigDecimal],
  commitId: Option[String],
  productId: Option[String],
  groupKey: Option[String],
  groupValue: Option[String],
  unitPrice: Option[BigDecimal],
  pricingGroupValues: Option[String],
  metadata: Option[String],
  subscriptionId: Option[String],
  isProrated: Option[Boolean],
  startingAt: Option[Instant],
  endingBefore: Option[Instant],
  updatedAt: Option[Instant]
) {
  lazy val isPrepaidCommit: Boolean = metadata.exists { metadata =>
    val json = Json.parse(metadata)
    (json \ "commit_type").asOpt[String].contains("PrepaidCommit")
  }
}

class MetronomeLineItemTable(tag: Tag) extends Table[MetronomeLineItem](tag, Some("metronome"), "line_item") {
  def id: Rep[String] = column[String]("id")
  def environmentType: Rep[Option[String]] = column[Option[String]]("environment_type")
  def invoiceId: Rep[Option[String]] = column[Option[String]]("invoice_id")
  def creditGrantId: Rep[Option[String]] = column[Option[String]]("credit_grant_id")
  def creditTypeId: Rep[Option[String]] = column[Option[String]]("credit_type_id")
  def creditTypeName: Rep[Option[String]] = column[Option[String]]("credit_type_name")
  def name: Rep[Option[String]] = column[Option[String]]("name")
  def quantity: Rep[Option[BigDecimal]] = column[Option[BigDecimal]]("quantity")
  def total: Rep[Option[BigDecimal]] = column[Option[BigDecimal]]("total")
  def commitId: Rep[Option[String]] = column[Option[String]]("commit_id")
  def productId: Rep[Option[String]] = column[Option[String]]("product_id")
  def groupKey: Rep[Option[String]] = column[Option[String]]("group_key")
  def groupValue: Rep[Option[String]] = column[Option[String]]("group_value")
  def unitPrice: Rep[Option[BigDecimal]] = column[Option[BigDecimal]]("unit_price")
  def pricingGroupValues: Rep[Option[String]] = column[Option[String]]("pricing_group_values")
  def metadata: Rep[Option[String]] = column[Option[String]]("metadata")
  def subscriptionId: Rep[Option[String]] = column[Option[String]]("subscription_id")
  def isProrated: Rep[Option[Boolean]] = column[Option[Boolean]]("is_prorated")
  def startingAt: Rep[Option[Instant]] = column[Option[Instant]]("starting_at")
  def endingBefore: Rep[Option[Instant]] = column[Option[Instant]]("ending_before")
  def updatedAt: Rep[Option[Instant]] = column[Option[Instant]]("updated_at")

  def * : ProvenShape[MetronomeLineItem] = (
    id,
    environmentType,
    invoiceId,
    creditGrantId,
    creditTypeId,
    creditTypeName,
    name,
    quantity,
    total,
    commitId,
    productId,
    groupKey,
    groupValue,
    unitPrice,
    pricingGroupValues,
    metadata,
    subscriptionId,
    isProrated,
    startingAt,
    endingBefore,
    updatedAt
  ).<>((MetronomeLineItem.apply _).tupled, MetronomeLineItem.unapply)
}

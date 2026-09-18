package database.models.metronome

import framework.Instant
import framework.PostgresProfile.api.*
import slick.lifted.{ProvenShape, Rep}

case class MetronomeCustomer(
  id: String,
  environmentType: Option[String],
  name: Option[String],
  ingestAliases: Option[String],
  salesforceAccountId: Option[String],
  billingProviderType: Option[String],
  billingProviderCustomerId: Option[String],
  customFields: Option[String],
  createdAt: Option[Instant],
  updatedAt: Option[Instant],
  archivedAt: Option[Instant]
)

class MetronomeCustomerTable(tag: Tag) extends Table[MetronomeCustomer](tag, Some("metronome"), "customer") {
  def id: Rep[String] = column[String]("id")
  def environmentType: Rep[Option[String]] = column[Option[String]]("environment_type")
  def name: Rep[Option[String]] = column[Option[String]]("name")
  def ingestAliases: Rep[Option[String]] = column[Option[String]]("ingest_aliases")
  def salesforceAccountId: Rep[Option[String]] = column[Option[String]]("salesforce_account_id")
  def billingProviderType: Rep[Option[String]] = column[Option[String]]("billing_provider_type")
  def billingProviderCustomerId: Rep[Option[String]] = column[Option[String]]("billing_provider_customer_id")
  def customFields: Rep[Option[String]] = column[Option[String]]("custom_fields")
  def createdAt: Rep[Option[Instant]] = column[Option[Instant]]("created_at")
  def updatedAt: Rep[Option[Instant]] = column[Option[Instant]]("updated_at")
  def archivedAt: Rep[Option[Instant]] = column[Option[Instant]]("archived_at")

  def * : ProvenShape[MetronomeCustomer] = (
    id,
    environmentType,
    name,
    ingestAliases,
    salesforceAccountId,
    billingProviderType,
    billingProviderCustomerId,
    customFields,
    createdAt,
    updatedAt,
    archivedAt
  ).<>((MetronomeCustomer.apply _).tupled, MetronomeCustomer.unapply)
}

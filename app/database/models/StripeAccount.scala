package database.models

import framework.Jsonable
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class StripeAccount(
  id: String,
  name: String,
  defaultCurrency: String,
  liveModeApiKey: Option[String],
  testModeApiKey: Option[String]
) extends Jsonable {
  // Never expose the API keys to the frontend; only whether each mode is configured.
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "name" -> name,
    "defaultCurrency" -> defaultCurrency,
    "liveModeEnabled" -> liveModeApiKey.isDefined,
    "testModeEnabled" -> testModeApiKey.isDefined
  )
}

class StripeAccountTable(tag: Tag) extends Table[StripeAccount](tag, "stripe_account") {
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def name: Rep[String] = column[String]("name")
  def defaultCurrency: Rep[String] = column[String]("default_currency")
  def liveModeApiKey: Rep[Option[String]] = column[Option[String]]("live_mode_api_key")
  def testModeApiKey: Rep[Option[String]] = column[Option[String]]("test_mode_api_key")

  def * : ProvenShape[StripeAccount] = (
    id,
    name,
    defaultCurrency,
    liveModeApiKey,
    testModeApiKey
  ).<>((StripeAccount.apply _).tupled, StripeAccount.unapply)
}

package database.models

import framework.Jsonable
import framework.PostgresProfile.api.*
import play.api.libs.json.{JsObject, Json}
import slick.lifted.{ProvenShape, Rep}

case class TaxRate(
  stripeAccountId: String,
  liveMode: Boolean,
  id: String,
  inclusive: Boolean,
  percentage: Double,
  flatAmount: Option[Long],
  flatAmountCurrency: Option[String],
  rateType: Option[String]
) extends Jsonable {
  def toJson(): JsObject = Json.obj(
    "id" -> id,
    "inclusive" -> inclusive,
    "percentage" -> percentage,
    "flatAmount" -> flatAmount,
    "flatAmountCurrency" -> flatAmountCurrency,
    "rateType" -> rateType,
  )

  def computeTax(amount: Long): Long = {
    rateType
      .flatMap {
        case "flat_amount" => flatAmount
        // TODO: The inclusive tax computation here is incorrect. Fix it.
        case "percentage" =>
          if (inclusive) {
            Some((percentage * amount / (100 + percentage)).toLong)
          } else {
            Some((percentage * amount / 100).toLong)
          }
      }
      .getOrElse(0L)
  }
}

class TaxRateTable(tag: Tag) extends Table[TaxRate](tag, "tax_rate") {
  def stripeAccountId: Rep[String] = column[String]("stripe_account_id")
  def liveMode: Rep[Boolean] = column[Boolean]("live_mode")
  def id: Rep[String] = column[String]("id", O.PrimaryKey)
  def inclusive: Rep[Boolean] = column[Boolean]("inclusive")
  def percentage: Rep[Double] = column[Double]("percentage")
  def flatAmount: Rep[Option[Long]] = column[Option[Long]]("flat_amount")
  def flatAmountCurrency: Rep[Option[String]] = column[Option[String]]("flat_amount_currency")
  def rateType: Rep[Option[String]] = column[Option[String]]("rate_type")

  def * : ProvenShape[TaxRate] = (
    stripeAccountId,
    liveMode,
    id,
    inclusive,
    percentage,
    flatAmount,
    flatAmountCurrency,
    rateType
  ).<>((TaxRate.apply _).tupled, TaxRate.unapply)
}

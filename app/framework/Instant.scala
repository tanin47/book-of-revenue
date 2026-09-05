package framework

import givers.form.{BindContext, Mapping, UnbindContext}
import play.api.libs.json.{JsDefined, JsLookupResult, JsNumber, JsString, JsValue}

import java.time.temporal.ChronoUnit
import scala.reflect.{ClassTag, classTag}
import scala.util.{Failure, Success, Try}

type Instant = java.time.Instant

object Instant {
  trait MockedTimeChangeListener {
    def mockedTimeChanged(time: Instant): Unit
  }

  private[this] var mockedTime: Option[Instant] = None
  var mockedTimeChangedListener: Option[MockedTimeChangeListener] = None

  def mockTimeForTest(t: Instant): Unit = {
    mockedTime = Some(t)
    mockedTimeChangedListener.foreach(_.mockedTimeChanged(mockedTime.get))
  }

  def parse(text: String): Instant = java.time.Instant.parse(text)

  def advancedTime(days: Int = 0, hours: Int = 0, minutes: Int = 0, seconds: Int = 0): Unit = {
    mockedTime = Some(
      mockedTime.get
        .plus(days, ChronoUnit.DAYS)
        .plus(hours, ChronoUnit.HOURS)
        .plus(minutes, ChronoUnit.MINUTES)
        .plus(seconds, ChronoUnit.SECONDS)
    )
    mockedTimeChangedListener.foreach(_.mockedTimeChanged(mockedTime.get))
  }

  def now(): Instant = mockedTime
    .getOrElse(
      java.time.Instant.now() // scalafix:ok
    )

  def ofEpochSecond(epochSecond: Long): Instant = java.time.Instant.ofEpochSecond(epochSecond)
  def ofEpochMilli(epochMilli: Long): Instant = java.time.Instant.ofEpochMilli(epochMilli)
  def max(a: Instant, b: Instant): Instant = if (a.isBefore(b)) { b } else { a }
  def min(a: Instant, b: Instant): Instant = if (a.isBefore(b)) { a } else { b }

  val form: Mapping[Instant] = new Mapping[Instant] {
    def bind(value: JsLookupResult, context: BindContext): Try[Instant] = {
      value match {
        case JsDefined(v: JsNumber) => Try(Instant.ofEpochMilli(v.value.toLong))
        case _ => Failure(Mapping.error("error.invalid"))
      }
    }

    def unbind(value: Instant, context: UnbindContext): JsValue = throw new UnsupportedOperationException()
  }


}

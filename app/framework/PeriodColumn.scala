package framework

import givers.form.{BindContext, Mapping, UnbindContext}
import play.api.libs.json.{JsDefined, JsLookupResult, JsObject, JsString, JsValue}

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, ZoneOffset}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

object PeriodColumn {
  def parse(text: String): Try[PeriodColumn] = {
    if (text.matches("\\d{4}-\\d{2}")) {
      val localDate = LocalDate.parse(s"$text-01", DateTimeFormatter.ISO_LOCAL_DATE)
      val instant = localDate.atStartOfDay(ZoneOffset.UTC).toInstant
      Success(PeriodColumn(instant.toEpochMilli))
    } else {
      Failure(new IllegalArgumentException(s"Invalid period: $text"))
    }
  }

  def form[T <: Enum[T] : ClassTag]: Mapping[T | PeriodColumn] = new Mapping[T | PeriodColumn] {
    val classTag: ClassTag[T] = implicitly[ClassTag[T]]
    def bind(value: JsLookupResult, context: BindContext): Try[T | PeriodColumn] = {
      value match {
        case JsDefined(v: JsString) =>
          try {
            Success(PeriodColumn.parse(v.value).getOrElse(classTag.runtimeClass.getMethod("valueOf", classOf[String]).invoke(null, v.value).asInstanceOf[T | PeriodColumn]))
          } catch {
            case _: Exception => Failure(Mapping.error("error.invalid"))
          }
        case _ => Failure(Mapping.error("error.invalid"))
      }
    }

    def unbind(value: T | PeriodColumn, context: UnbindContext): JsValue = throw new UnsupportedOperationException()
  }
}

case class PeriodColumn(period: Long) {
  val name: String = LocalDate
    .ofInstant(java.time.Instant.ofEpochMilli(period), ZoneOffset.UTC)
    .format(DateTimeFormatter.ISO_LOCAL_DATE)
    .substring(0, 7)

  override def toString: String = name
}

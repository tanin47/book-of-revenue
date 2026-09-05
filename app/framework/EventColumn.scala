package framework

import database.models.JournalEntry
import givers.form.{BindContext, Mapping, UnbindContext}
import play.api.libs.json.{JsDefined, JsLookupResult, JsString, JsValue}

import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

object EventColumn {
  def parse(text: String): Try[EventColumn] = { Try(JournalEntry.Event.valueOf(text)).map(EventColumn.apply) }

  def form[T <: Enum[T] : ClassTag]: Mapping[T | EventColumn] = new Mapping[T | EventColumn] {
    val classTag: ClassTag[T] = implicitly[ClassTag[T]]
    def bind(value: JsLookupResult, context: BindContext): Try[T | EventColumn] = {
      value match {
        case JsDefined(v: JsString) =>
          try {
            Success(EventColumn.parse(v.value).getOrElse(classTag.runtimeClass.getMethod("valueOf", classOf[String]).invoke(null, v.value).asInstanceOf[T | EventColumn]))
          } catch {
            case e: Exception =>
              Failure(Mapping.error("error.invalid"))
          }
        case _ => Failure(Mapping.error("error.invalid"))
      }
    }

    def unbind(value: T | EventColumn, context: UnbindContext): JsValue = throw new UnsupportedOperationException()
  }
}

case class EventColumn(event: JournalEntry.Event) {
  val name: String = event.name

  override def toString: String = name
}

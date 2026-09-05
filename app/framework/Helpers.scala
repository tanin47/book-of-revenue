package framework

import background.{ProcessTransactionWorkerRequest, StripeEventImporterRequest, StripeImporterRequest, StripeMeterEventSummaryImporterRequest, StripeNormalizer, StripeNormalizerRequest}
import database.models.JournalEntry
import database.services.JournalEntryService
import database.services.JournalEntryService.ColumnType
import givers.form.*
import org.jobrunr.scheduling.{JobRequestScheduler, JobScheduler}
import play.api.libs.json.{JsDefined, JsLookupResult, JsString, JsValue}

import java.time.{LocalTime, YearMonth, ZoneId}
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.{Executors, TimeUnit}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}
import scala.reflect.{ClassTag, classTag}
import scala.util.{Failure, Success, Try}

object Helpers {
  def queueBootstrapJobs(jobScheduler: JobRequestScheduler): Unit = {
    jobScheduler.enqueue(StripeImporterRequest())
    jobScheduler.enqueue(StripeEventImporterRequest())
    jobScheduler.enqueue(StripeNormalizerRequest())
    jobScheduler.enqueue(StripeMeterEventSummaryImporterRequest())
    jobScheduler.enqueue(StripeNormalizerRequest())
    jobScheduler.enqueue(ProcessTransactionWorkerRequest())
  }

  def makeValidationException(key: String, args: String*): ValidationException = {
    new ValidationException(Seq(new ValidationMessage(key, args: _*)))
  }

  def constantForm[T](constant: T): Mapping[T] = new Mapping[T] {
    def bind(value: JsLookupResult, context: BindContext): Try[T] = Success(constant)
    def unbind(value: T, context: UnbindContext): JsValue = throw new UnsupportedOperationException()
  }

  def enumForm[T <: Enum[T]: ClassTag]: Mapping[T] = new Mapping[T] {
    def bind(value: JsLookupResult, context: BindContext): Try[T] = {
      value match {
        case JsDefined(v: JsString) =>
          val method = classTag[T].runtimeClass.getMethod("valueOf", classOf[String])
          try {
            Success(method.invoke(null, v.value).asInstanceOf[T])
          } catch {
            case _: Exception =>
              Failure(Mapping.error("error.invalid", v.value))
          }
        case _ => Failure(Mapping.error("error.invalid"))
      }
    }

    def unbind(value: T, context: UnbindContext): JsValue = throw new UnsupportedOperationException()
  }

  def await[T](future: Future[T]): T = Await.result(future, Duration.Inf)

  private[this] val scheduler = Executors.newSingleThreadScheduledExecutor()
  def sleep(ms: Long): Future[Unit] = {
    val promise = Promise[Unit]()
    scheduler.schedule(
      new Runnable { override def run(): Unit = promise.success(()) },
      ms,
      TimeUnit.MILLISECONDS
    )
    promise.future
  }

  def printEntries(entries: Seq[JournalEntry]): Unit = {
    entries.foreach { entry =>
      println(s"period: ${entry.accountingPeriod}, occurredAt: ${entry.occurredAt}, debit: ${entry.debit}, credit: ${entry.credit}, settlement: ${entry.settlementAmount}, presentment: ${entry.presentmentAmount}")
    }
  }

  def formatCsvValue(value: Option[Any], columnType: JournalEntryService.ColumnType): String = {
    value match {
      case None => ""
      case Some(v) => escapeCsv(columnType match {
        case ColumnType.Period => Instant.ofEpochMilli(v.asInstanceOf[Long]).toString.substring(0, 7)
        case ColumnType.Date => Instant.ofEpochMilli(v.asInstanceOf[Long]).toString.substring(0, 10)
        case ColumnType.Timestamp => Instant.ofEpochMilli(v.asInstanceOf[Long]).toString
        case ColumnType.Amount | ColumnType.DeltaAmount => "%.2f".format(v.asInstanceOf[Long].toDouble / 100)
        case ColumnType.Percentage => "%.2f".format(v.asInstanceOf[Double])
        case ColumnType.Number | ColumnType.String => v.toString
      })
    }
  }

  def escapeCsv(value: String): String = {
    if (value.exists { c => c == ',' || c == '"' || c == '\n' || c == '\r' }) {
      "\"" + value.replace("\"", "\"\"") + "\""
    } else {
      value
    }
  }

  def toMonthEnd(instant: Instant): Instant = {
    val utc = ZoneId.of("UTC")
    YearMonth.from(instant.atZone(utc)).atEndOfMonth().atTime(LocalTime.MAX).atZone(utc).toInstant()
  }
}

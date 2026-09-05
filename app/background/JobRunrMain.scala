package background

import modules.JobRunrBaseConfiguration
import org.jobrunr.configuration.JobRunrConfiguration
import org.jobrunr.dashboard.JobRunrDashboardWebServerConfiguration
import org.jobrunr.scheduling.JobRequestScheduler
import org.jobrunr.server.BackgroundJobServer
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{Application, Environment, Mode, Play}

import javax.inject.{Inject, Singleton}

object JobRunrMain {
  def main(args: Array[String]): Unit = {
    val app = GuiceApplicationBuilder(Environment.simple(mode = Mode.Prod)).build()

    Play.start(app)
    new JobRunrMain(app).start()

    Thread.currentThread().join()
  }
}

@Singleton
class JobRunrMain @Inject()(app: Application) {
  lazy val jobRunrConfig: JobRunrConfiguration = app.injector
    .instanceOf[JobRunrBaseConfiguration]
    .get()
    .useDashboard(
      JobRunrDashboardWebServerConfiguration
        .usingStandardDashboardConfiguration()
        .andPort(8000)
    )

  // This will be used in tests
  lazy val backgroundJobServer: BackgroundJobServer = {
    val field = jobRunrConfig.getClass.getDeclaredField("backgroundJobServer")
    field.setAccessible(true)
    field.get(jobRunrConfig).asInstanceOf[BackgroundJobServer]
  }

  def start(): Unit = {
    val _ = jobRunrConfig.initialize()

    val jobScheduler = app.injector.instanceOf[JobRequestScheduler]

    jobScheduler.scheduleRecurrently("stripe-importer", "0 0 * * *", StripeImporterRequest())
    jobScheduler.scheduleRecurrently("stripe-event-importer", "0/30 * * * *", StripeEventImporterRequest())
    jobScheduler.scheduleRecurrently("stripe-incremental-importer", "0/30 * * * *", StripeIncrementalImporterRequest())
    jobScheduler.scheduleRecurrently("stripe-normalizer", "10,40 * * * *", StripeNormalizerRequest())
    jobScheduler.scheduleRecurrently("process-transaction-worker", "20,50 * * * *", ProcessTransactionWorkerRequest())
    jobScheduler.scheduleRecurrently("stripe-meter-event-summary-importer", "0 1 * * *", StripeMeterEventSummaryImporterRequest())

    backgroundJobServer.start()
  }

  def stop(): Unit = {
    backgroundJobServer.stop()
  }
}

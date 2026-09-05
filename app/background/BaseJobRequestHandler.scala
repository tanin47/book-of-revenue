package background

import database.services.TrackedExceptionService
import framework.Helpers.await
import org.jobrunr.jobs.lambdas.{JobRequest, JobRequestHandler}

abstract class BaseJobRequestHandler[T <: JobRequest](trackedExceptionService: TrackedExceptionService) extends JobRequestHandler[T] {
  def run2(jobRequest: T): Unit

  final def run(jobRequest: T): Unit = {
    try {
      run2(jobRequest)
    } catch { case e: Exception =>
      val _ = await(trackedExceptionService.create(e))
      throw e
    }
  }
}

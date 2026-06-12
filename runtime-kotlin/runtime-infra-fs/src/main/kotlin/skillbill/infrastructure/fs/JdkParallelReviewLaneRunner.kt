package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.review.ParallelReviewLaneRunner
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunRequest
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@Inject
class JdkParallelReviewLaneRunner : ParallelReviewLaneRunner {
  override fun runTwoLanes(request: ParallelReviewLaneRunRequest): ParallelReviewLaneRunResult {
    val executor = Executors.newFixedThreadPool(2)
    var outcome1 = ParallelReviewLaneOutcome(false, "", "interrupted while waiting for lanes")
    var outcome2 = ParallelReviewLaneOutcome(false, "", "interrupted while waiting for lanes")
    try {
      val futures = executor.invokeAll(
        listOf(
          Callable { request.lane1() },
          Callable { request.lane2() },
        ),
        request.timeout.inWholeSeconds,
        TimeUnit.SECONDS,
      )
      outcome1 = resultFromFuture(futures[0])
      outcome2 = resultFromFuture(futures[1])
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    } finally {
      executor.shutdownNow()
      try {
        executor.awaitTermination(DESTROY_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
    return ParallelReviewLaneRunResult(outcome1, outcome2)
  }

  private fun resultFromFuture(future: Future<ParallelReviewLaneOutcome>): ParallelReviewLaneOutcome = try {
    future.get()
  } catch (_: CancellationException) {
    ParallelReviewLaneOutcome(false, "", "lane timed out (cancelled by shared budget)")
  } catch (e: ExecutionException) {
    val cause = e.cause ?: e
    ParallelReviewLaneOutcome(
      success = false,
      rawOutput = "",
      failureReason = "lane launch threw ${cause::class.simpleName}: ${cause.message ?: "no detail"}",
    )
  } catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    ParallelReviewLaneOutcome(false, "", "interrupted while collecting lane result")
  }

  private companion object {
    const val DESTROY_WAIT_TIMEOUT_MILLIS = 5_000L
  }
}

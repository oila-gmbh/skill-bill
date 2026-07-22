package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.DelegatedReviewExecutionOutcome
import skillbill.application.review.model.DelegatedReviewExecutionRequest
import skillbill.application.review.model.DelegatedReviewLaunchOutcome
import skillbill.application.review.model.DelegatedReviewWorkerRequest

/**
 * Production entry point for a delegated specialist. A caller cannot start a worker without first
 * obtaining the broker's validated, bounded projection.
 */
@Inject
class DelegatedReviewExecutionBroker(
  private val launchBroker: DelegatedReviewLaunchBroker,
  private val workerLauncher: DelegatedReviewWorkerLauncher,
) {
  /** Validates every launch boundary before any inline or delegated worker is allowed to start. */
  fun preflight(requests: List<skillbill.application.review.model.DelegatedReviewLaunchRequest>) {
    requests.forEach(launchBroker::prepare)
  }

  fun execute(request: DelegatedReviewExecutionRequest): DelegatedReviewExecutionOutcome =
    when (val prepared = launchBroker.prepare(request.launchRequest)) {
      is DelegatedReviewLaunchOutcome.Terminated ->
        DelegatedReviewExecutionOutcome.Terminated(prepared.outcome)
      is DelegatedReviewLaunchOutcome.Prepared -> DelegatedReviewExecutionOutcome.Completed(
        workerLauncher.launch(
          DelegatedReviewWorkerRequest(
            prepared = prepared,
            agentId = request.launchRequest.agentId,
            repoRoot = request.repoRoot,
            timeout = request.timeout,
            modelOverride = request.modelOverride,
          ),
        ),
      )
    }
}

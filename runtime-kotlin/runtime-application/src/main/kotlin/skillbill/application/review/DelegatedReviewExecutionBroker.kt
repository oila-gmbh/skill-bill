package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.DelegatedReviewExecutionOutcome
import skillbill.application.review.model.DelegatedReviewExecutionRequest
import skillbill.application.review.model.DelegatedReviewLaunchOutcome
import skillbill.application.review.model.DelegatedReviewWorkerRequest
import skillbill.ports.review.model.ReviewLaneAccounting

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
        DelegatedReviewExecutionOutcome.Terminated(
          prepared.outcome,
          ReviewLaneAccounting(
            lane = request.launchRequest.assignment.lane,
            reviewId = request.launchRequest.assignment.reviewId,
            packetDigest = request.launchRequest.assignment.packetDigest,
            assignmentDigest = request.launchRequest.assignment.digest,
            launchBytes = 0,
            evidenceBytes = 0,
            expansions = emptyList(),
            toolCalls = 0,
            modelTurns = 0,
            resultBytes = 0,
            terminalStatus = prepared.outcome.type,
            terminalOutcome = prepared.outcome,
          ),
        )
      is DelegatedReviewLaunchOutcome.Prepared -> DelegatedReviewExecutionOutcome.Completed(
        workerLauncher.launch(
          DelegatedReviewWorkerRequest(
            prepared = prepared,
            agentId = request.launchRequest.agentId,
            repoRoot = request.repoRoot,
            timeout = request.timeout,
            logicalWorkerName = request.launchRequest.logicalWorkerName,
            modelOverride = request.modelOverride,
          ),
        ),
      )
    }
}

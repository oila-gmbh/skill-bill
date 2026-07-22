package skillbill.ports.review

import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.review.model.NativeReviewWorkerRequest

fun interface NativeReviewWorkerLauncher {
  fun launch(request: NativeReviewWorkerRequest): AgentRunLaunchOutcome
}

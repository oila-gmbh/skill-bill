package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.review.NativeReviewWorkerLauncher
import skillbill.ports.review.NativeReviewWorkerRequest

/** Preserves the provider-native request instead of degrading it into a general process launch. */
@Inject
class GoalRunnerNativeReviewWorkerAdapter(
  private val launcher: AgentRunLauncher,
) : NativeReviewWorkerLauncher {
  override fun launch(request: NativeReviewWorkerRequest) = launcher.launchNativeReview(request)
}

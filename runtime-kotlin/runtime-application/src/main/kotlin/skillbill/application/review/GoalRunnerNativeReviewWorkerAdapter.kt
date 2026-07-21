package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.NativeReviewWorkerLauncher
import skillbill.ports.review.NativeReviewWorkerRequest

/** Materializes the provider-native request at the existing agent-launch gateway. */
@Inject
class GoalRunnerNativeReviewWorkerAdapter(
  private val launcher: GoalRunnerSubtaskLauncher,
) : NativeReviewWorkerLauncher {
  override fun launch(request: NativeReviewWorkerRequest) = launcher.launch(
    GoalRunnerSubtaskLaunchRequest(
      invokedAgentId = request.agentId,
      configuredAgentOverrideId = null,
      skillRunRequest = SkillRunRequest(
        issueKey = request.issueKey,
        repoRoot = request.repoRoot,
        timeout = request.timeout,
        promptOverride = request.prompt,
        modelOverride = request.modelOverride,
        conversationIsolation = ConversationIsolation.NONE,
        reviewEvidenceBroker = request.broker,
        outputSink = { stream, text ->
          if (stream == skillbill.ports.agentrun.model.AgentRunOutputStream.STDOUT) {
            request.broker.observeLaneResultChunk(text)
          }
        },
      ),
    ),
  )
}

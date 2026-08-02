package skillbill.launcher.agentrun

import skillbill.install.model.InstallAgent
import skillbill.ports.review.model.DelegatedReviewCapabilityDimensions
import skillbill.ports.review.model.DelegatedReviewProviderCapability
import skillbill.ports.review.model.DelegatedReviewProviderCapabilityMatrix
import skillbill.ports.review.model.DelegatedReviewProviderStatus

object DelegatedReviewProviderCapabilityRegistry {
  private val fullyObserved = DelegatedReviewCapabilityDimensions(
    freshContextIsolation = true,
    workerTracking = true,
    outputCapture = true,
    declaredProgress = true,
    cancellation = true,
    timeout = true,
    tokenReporting = true,
    terminalResult = true,
  )

  fun forProvider(agent: InstallAgent): DelegatedReviewProviderCapability = when (agent) {
    InstallAgent.CODEX, InstallAgent.CLAUDE, InstallAgent.CURSOR -> DelegatedReviewProviderCapability(
      providerId = agent.id,
      status = DelegatedReviewProviderStatus.EXPERIMENTAL,
      dimensions = fullyObserved,
      rationale = "Adapter and process fixtures cover isolated launch, bounded output, lifecycle callbacks, " +
        "cancellation, deadlines, completion usage, and explicit terminal classification; live promotion is deferred.",
    )
    InstallAgent.JUNIE -> unsupported(
      agent,
      "Fresh-context delegated workers do not expose the lifecycle and terminal-result contract.",
    )
    else -> unsupported(
      agent,
      "No delegated worker adapter is registered for this provider.",
    )
  }

  fun matrix(): List<DelegatedReviewProviderCapability> =
    InstallAgent.entries.map(::forProvider)

  fun matrixProjection(): DelegatedReviewProviderCapabilityMatrix =
    DelegatedReviewProviderCapabilityMatrix(matrix())

  private fun unsupported(agent: InstallAgent, rationale: String) = DelegatedReviewProviderCapability(
    providerId = agent.id,
    status = DelegatedReviewProviderStatus.UNSUPPORTED,
    dimensions = DelegatedReviewCapabilityDimensions(
      freshContextIsolation = false,
      workerTracking = false,
      outputCapture = false,
      declaredProgress = false,
      cancellation = false,
      timeout = false,
      tokenReporting = false,
      terminalResult = false,
    ),
    rationale = rationale,
  )
}

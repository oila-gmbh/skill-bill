package skillbill.launcher.agentrun

import skillbill.install.model.InstallAgent
import skillbill.ports.review.model.DelegatedReviewCapabilityDimensions
import skillbill.ports.review.model.DelegatedReviewProviderCapability
import skillbill.ports.review.model.DelegatedReviewProviderCapabilityMatrix
import skillbill.ports.review.model.DelegatedReviewProviderStatus

object DelegatedReviewProviderCapabilityRegistry {
  private const val FAILURE_ITEM_IDS = "items=13,14,15,16,17,18,24,25,28,33,40,42,47"

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
    InstallAgent.CODEX -> DelegatedReviewProviderCapability(
      providerId = agent.id,
      status = DelegatedReviewProviderStatus.EXPERIMENTAL,
      dimensions = fullyObserved,
      rationale = "Codex keeps fork_turns none and its provider strategy; coordinator capacity, bootstrap " +
        "measurement, five deadline scopes, bounded diagnostics, repair, wave telemetry, and terminal behavior " +
        "are recorded, while promotion remains deferred pending independent canaries. " +
          failureDisposition(agent),
    )
    InstallAgent.CLAUDE -> DelegatedReviewProviderCapability(
      providerId = agent.id,
      status = DelegatedReviewProviderStatus.EXPERIMENTAL,
      dimensions = fullyObserved,
      rationale = "Claude keeps fresh-process isolation, streamed output decoding, and its callback strategy; " +
        "shared capacity, bootstrap measurement, five deadline scopes, bounded repair, wave telemetry, and " +
        "terminal behavior are recorded, while promotion remains deferred pending independent canaries. " +
          failureDisposition(agent),
    )
    InstallAgent.CURSOR -> DelegatedReviewProviderCapability(
      providerId = agent.id,
      status = DelegatedReviewProviderStatus.EXPERIMENTAL,
      dimensions = fullyObserved,
      rationale = "Cursor keeps its independent fresh-process and stream strategy; shared capacity, bootstrap " +
        "measurement, five deadline scopes, bounded repair, wave telemetry, and terminal behavior are recorded, " +
        "while promotion remains deferred pending independent canaries. " + failureDisposition(agent),
    )
    InstallAgent.JUNIE -> unsupported(
      agent,
      "Fresh-context delegated workers do not expose the lifecycle and terminal-result contract. " +
        failureDisposition(agent),
    )
    else -> unsupported(
      agent,
      "No delegated worker adapter is registered for this provider. " + failureDisposition(agent),
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

  private fun failureDisposition(agent: InstallAgent): String = when (agent) {
    InstallAgent.CODEX, InstallAgent.CLAUDE, InstallAgent.CURSOR ->
      "$FAILURE_ITEM_IDS; capacity=mitigated; bootstrap=mitigated; deadlines=mitigated; " +
        "diagnostics=mitigated; repair=mitigated; waves=mitigated; promotion=deferred pending canaries."
    else ->
      "$FAILURE_ITEM_IDS; capacity=unsupported/deferred; bootstrap=unsupported/deferred; " +
        "deadlines=unsupported/deferred; diagnostics=unsupported/deferred; repair=unsupported/deferred; " +
        "waves=unsupported/deferred; promotion=unsupported/deferred until an independent adapter exists."
  }
}

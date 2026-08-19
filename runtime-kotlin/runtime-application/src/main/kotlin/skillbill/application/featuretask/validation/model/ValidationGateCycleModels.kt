package skillbill.application.featuretask.validation.model

import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRunRecord
import java.nio.file.Path

enum class ValidationGateCyclePhase {
  INITIAL_DISCOVERY,
  POST_REPAIR_VERIFY,
}

sealed interface ValidationGateResolution {
  data class Declared(
    val packSlug: String,
    val declaration: ValidationGateDeclaration,
  ) : ValidationGateResolution

  /** Missing pack gate declaration — agent-run validate fallback with surfaced degradation. */
  data class Absent(val routedPackSlug: String?) : ValidationGateResolution

  /** Installed packs exist but could not be read against this runtime's contract. */
  data class Incompatible(val reason: String) : ValidationGateResolution
}

data class ValidationFindingSetProjection(
  val findings: List<ValidationGateFinding>,
) {
  fun toHandoffMaps(): List<Map<String, String?>> = findings.map { finding ->
    linkedMapOf(
      "module" to finding.module,
      "rule_or_test_id" to finding.ruleOrTestId,
      "message" to finding.message,
      "location" to finding.location,
    )
  }
}

/** Agent repair launch within the runtime-owned validate gate cycle. */
fun interface ValidationGateAgentRepairLauncher {
  fun launch(findings: ValidationFindingSetProjection, repairIteration: Int): ValidationGateAgentRepairResult
}

sealed interface ValidationGateAgentRepairResult {
  data class Completed(val output: FeatureTaskRuntimePhaseOutput) : ValidationGateAgentRepairResult
  data class Blocked(val reason: String) : ValidationGateAgentRepairResult
}

sealed interface ValidationGateCycleResult {
  /** Fall back to legacy agent-run validate (absent gate declaration). */
  data object AbsentFallback : ValidationGateCycleResult

  data class Terminal(val outcome: ValidationGateCycleTerminalOutcome) : ValidationGateCycleResult
}

sealed interface ValidationGateCycleTerminalOutcome {
  data class Completed(val output: FeatureTaskRuntimePhaseOutput) : ValidationGateCycleTerminalOutcome

  data class Blocked(
    val reason: String,
    val remainingFindings: ValidationFindingSetProjection? = null,
    val measurements: List<FeatureTaskRuntimeValidationGateRunRecord> = emptyList(),
  ) : ValidationGateCycleTerminalOutcome
}

/** Durable (or test) sink for live validate-gate progress, including remaining findings on exhaust. */
fun interface ValidationGateProgressStore {
  fun persist(workflowId: String, progress: FeatureTaskRuntimeValidationGateProgress, dbOverride: String?)

  fun load(workflowId: String, dbOverride: String?): FeatureTaskRuntimeValidationGateProgress? = null
}

/** Inputs for one runtime-owned validate gate cycle. */
data class ValidationGateCycleRequest(
  val repoRoot: Path,
  val request: FeatureTaskRuntimeRunRequest,
  val validationDepth: ValidationDepth,
  val changedPaths: List<String>,
  val repositoryCheckpoint: String,
  val agentRepairLauncher: ValidationGateAgentRepairLauncher,
)

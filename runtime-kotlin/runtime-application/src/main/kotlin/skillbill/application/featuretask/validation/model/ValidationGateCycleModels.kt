package skillbill.application.featuretask.validation.model

import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRepairWindowPhase
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

const val UNPARSEABLE_GATE_FAILURE_RULE_ID: String = "unparseable_gate_failure"

fun requiresUnparseableGateTriage(findings: List<ValidationGateFinding>): Boolean =
  findings.size == 1 && findings.single().ruleOrTestId == UNPARSEABLE_GATE_FAILURE_RULE_ID

fun interface ValidationGateAgentTriageLauncher {
  fun launch(findings: ValidationFindingSetProjection): ValidationGateTriageResult
}

sealed interface ValidationGateTriageResult {
  data class Captured(val validationRepairPlan: String) : ValidationGateTriageResult
  data object Empty : ValidationGateTriageResult
}

/** Agent repair launch within the runtime-owned validate gate cycle. */
fun interface ValidationGateAgentRepairLauncher {
  fun launch(
    findings: ValidationFindingSetProjection,
    repairIteration: Int,
    triagePlan: String?,
  ): ValidationGateAgentRepairResult
}

sealed interface ValidationGateAgentRepairResult {
  data class Completed(val output: FeatureTaskRuntimePhaseOutput) : ValidationGateAgentRepairResult
  data class Blocked(
    val reason: String,
    val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  ) : ValidationGateAgentRepairResult
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
    val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  ) : ValidationGateCycleTerminalOutcome
}

/** Durable (or test) sink for live validate-gate progress, including remaining findings on exhaust. */
fun interface ValidationGateProgressStore {
  fun persist(workflowId: String, progress: FeatureTaskRuntimeValidationGateProgress, dbOverride: String?)

  fun load(workflowId: String, dbOverride: String?): FeatureTaskRuntimeValidationGateProgress? = null
}

data class ValidationGateProgressWrite(
  val repairWindowPhase: FeatureTaskRuntimeValidationGateRepairWindowPhase,
  val remainingFindings: ValidationFindingSetProjection?,
  val completeFindings: List<ValidationGateFinding>,
  val repairsUsed: Int,
  val capturedTriagePlan: String?,
) {
  companion object {
    fun findingsOpen(
      completeFindings: List<ValidationGateFinding>,
      repairsUsed: Int,
      capturedTriagePlan: String?,
      remainingFindings: ValidationFindingSetProjection? = null,
    ): ValidationGateProgressWrite = ValidationGateProgressWrite(
      repairWindowPhase = FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN,
      remainingFindings = remainingFindings,
      completeFindings = completeFindings,
      repairsUsed = repairsUsed,
      capturedTriagePlan = capturedTriagePlan,
    )
  }
}

/** Inputs for one runtime-owned validate gate cycle. */
data class ValidationGateCycleRequest(
  val repoRoot: Path,
  val request: FeatureTaskRuntimeRunRequest,
  val validationDepth: ValidationDepth,
  val changedPaths: List<String>,
  val repositoryCheckpoint: String,
  val agentRepairLauncher: ValidationGateAgentRepairLauncher,
  val agentTriageLauncher: ValidationGateAgentTriageLauncher = ValidationGateAgentTriageLauncher {
    ValidationGateTriageResult.Empty
  },
)

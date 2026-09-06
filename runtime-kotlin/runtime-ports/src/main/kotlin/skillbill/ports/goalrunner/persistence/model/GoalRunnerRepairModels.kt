package skillbill.ports.goalrunner.persistence.model

import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.model.WorkflowId
import java.nio.file.Path

enum class GoalRunnerWedgeClass(val wireValue: String, val durableField: String) {
  MISSING_VALIDATION_DEPTH("missing_validation_depth", "validation_depth"),
  MISSING_QUALITY_GATE_SELECTION("missing_quality_gate_selection", "quality_gate_selection"),
  UNREACHABLE_REVIEW_BASE("unreachable_review_base", "review_base_sha"),
  UNREACHABLE_REMEDIATION_BASE("unreachable_remediation_base", "remediation_base_sha"),
  STALE_BLOCKED_CONTINUATION_OUTCOME("stale_blocked_continuation_outcome", "goal_continuation_outcome"),
  COMPLETED_UPSTREAM_MISSING_OUTPUT("completed_upstream_missing_output", "phase_output"),
  PHASE_OUTPUT_CONTRACT_INCOMPATIBLE("phase_output_contract_incompatible", "phase_output_contract_version"),
  ;

  companion object {
    fun fromWire(value: String): GoalRunnerWedgeClass = entries.firstOrNull { it.wireValue == value }
      ?: error("Unknown goal-repair wedge class '$value'.")
  }

  val operatorRequired: Boolean
    get() = this == PHASE_OUTPUT_CONTRACT_INCOMPATIBLE
}

enum class GoalRunnerRepairStatus(val wireValue: String) {
  INSPECTED("inspected"),
  REPAIRED("repaired"),
  HEALTHY("healthy"),
  NOT_WEDGED("not_wedged"),
  LIVE_LEASE_REFUSED("live_lease_refused"),
  OPERATOR_REQUIRED("operator_required"),
  NOT_FOUND("not_found"),
}

data class GoalRunnerRepairRequest(
  val issueKey: IssueKey,
  val apply: Boolean = false,
  val subtaskId: SubtaskId? = null,
  val repoRoot: Path? = null,
) {
  init {
    require(issueKey.value.isNotBlank()) { "issueKey is required." }
    require(subtaskId == null || subtaskId.value > 0) { "subtaskId must be positive." }
  }
}

data class GoalRunnerWedgeFinding(
  val wedgeClass: GoalRunnerWedgeClass,
  val field: String,
  val currentValue: String?,
)

data class GoalRunnerChildWedgeDiagnosis(
  val subtaskId: SubtaskId,
  val workflowId: WorkflowId?,
  val wedges: List<GoalRunnerWedgeFinding> = emptyList(),
  val passedChecks: List<String> = emptyList(),
) {
  val isHealthy: Boolean get() = wedges.isEmpty()
}

data class GoalRunnerChildWedgeDiagnosisRequest(
  val workflowId: WorkflowId,
  val issueKey: IssueKey,
  val subtaskId: SubtaskId,
  val subtasks: List<DecompositionSubtask>,
  val repoRoot: Path,
)

data class GoalRunnerChildWedgeRepairRequest(
  val workflowId: WorkflowId,
  val issueKey: IssueKey,
  val subtaskId: SubtaskId,
  val wedgeClasses: List<GoalRunnerWedgeClass>,
  val repoRoot: Path,
)

data class GoalRunnerChildRepairApplyRequest(
  val unitOfWork: GoalRunnerPersistenceSession,
  val workflowId: WorkflowId,
  val issueKey: IssueKey,
  val subtaskId: SubtaskId,
  val wedgeClasses: List<GoalRunnerWedgeClass>,
  val repoRoot: Path,
)

data class GoalRunnerChildRepairApplyResult(
  val repairs: List<GoalRunnerAppliedRepair> = emptyList(),
  val manifestProjectionArtifactsJson: String? = null,
)

data class GoalRunnerAppliedRepair(
  val subtaskId: SubtaskId,
  val workflowId: WorkflowId,
  val wedgeClass: GoalRunnerWedgeClass,
  val field: String,
  val priorValue: String?,
  val newValue: String?,
)

data class GoalRunnerRepairResult(
  val issueKey: IssueKey,
  val status: GoalRunnerRepairStatus,
  val parentWorkflowId: WorkflowId? = null,
  val diagnoses: List<GoalRunnerChildWedgeDiagnosis> = emptyList(),
  val appliedRepairs: List<GoalRunnerAppliedRepair> = emptyList(),
  val refusalReason: String? = null,
  val liveLeaseWorkflowId: String? = null,
) {
  init {
    require(issueKey.value.isNotBlank()) { "issueKey is required." }
  }
}

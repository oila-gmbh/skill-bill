package skillbill.application.goalrunner

import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyResult
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeDiagnosis
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeDiagnosisRequest
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeRepairRequest
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairStore as GoalRunnerChildRepairStorePort

typealias GoalRunnerChildRepairStore = GoalRunnerChildRepairStorePort

object NoopGoalRunnerChildRepairStore : GoalRunnerChildRepairStore {
  private const val NAME = "NoopGoalRunnerChildRepairStore"

  override fun diagnoseChildWedges(request: GoalRunnerChildWedgeDiagnosisRequest): GoalRunnerChildWedgeDiagnosis {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "diagnoseChildWedges(workflowId=${request.workflowId})")
    return GoalRunnerChildWedgeDiagnosis(
      subtaskId = request.subtaskId,
      workflowId = request.workflowId,
      passedChecks = listOf(
        "validation_depth_present",
        "quality_gate_selection_present",
        "review_base_reachable",
        "remediation_base_reachable_or_absent",
        "continuation_outcome_corroborated_or_absent",
        "upstream_output_present",
      ),
    )
  }

  override fun applyChildWedgeRepairs(request: GoalRunnerChildWedgeRepairRequest): GoalRunnerChildRepairApplyResult {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "applyChildWedgeRepairs(workflowId=${request.workflowId})")
    return GoalRunnerChildRepairApplyResult()
  }
}

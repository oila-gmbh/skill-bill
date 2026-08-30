package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalRunnerChildRepairApplyResult
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeDiagnosisRequest
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeRepairRequest
import skillbill.application.goalrunner.model.GoalRunnerWedgeClass

interface GoalRunnerChildRepairStore {
  fun diagnoseChildWedges(request: GoalRunnerChildWedgeDiagnosisRequest): GoalRunnerChildWedgeDiagnosis

  fun applyChildWedgeRepairs(request: GoalRunnerChildWedgeRepairRequest): GoalRunnerChildRepairApplyResult
}

object NoopGoalRunnerChildRepairStore : GoalRunnerChildRepairStore {
  override fun diagnoseChildWedges(request: GoalRunnerChildWedgeDiagnosisRequest): GoalRunnerChildWedgeDiagnosis =
    GoalRunnerChildWedgeDiagnosis(
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

  override fun applyChildWedgeRepairs(request: GoalRunnerChildWedgeRepairRequest): GoalRunnerChildRepairApplyResult =
    GoalRunnerChildRepairApplyResult()
}

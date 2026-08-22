package skillbill.application.goalrunner

import skillbill.application.model.GoalRunnerChildRepairApplyResult
import skillbill.application.model.GoalRunnerChildWedgeDiagnosis
import skillbill.application.model.GoalRunnerWedgeClass
import skillbill.workflow.model.DecompositionSubtask
import java.nio.file.Path

/**
 * SKILL-176 operator repair seam: diagnose and clear known goal-child wedge classes without
 * discarding completed work. Implemented by [WorkflowGoalRunnerOutcomeStore].
 */
interface GoalRunnerChildRepairStore {
  @Suppress("LongParameterList") // one child repair context; each field is required for diagnosis
  fun diagnoseChildWedges(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    subtasks: List<DecompositionSubtask>,
    repoRoot: Path,
    dbPathOverride: String? = null,
  ): GoalRunnerChildWedgeDiagnosis

  /**
   * Applies [wedgeClasses] for one child inside a single durable transaction. Writes the field
   * patch and repair-evidence artifact together; a failure mid-way leaves the row unchanged.
   */
  @Suppress("LongParameterList") // one child repair context; each field is required for the durable write
  fun applyChildWedgeRepairs(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    wedgeClasses: List<GoalRunnerWedgeClass>,
    repoRoot: Path,
    dbPathOverride: String? = null,
  ): GoalRunnerChildRepairApplyResult
}

object NoopGoalRunnerChildRepairStore : GoalRunnerChildRepairStore {
  override fun diagnoseChildWedges(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    subtasks: List<DecompositionSubtask>,
    repoRoot: Path,
    dbPathOverride: String?,
  ): GoalRunnerChildWedgeDiagnosis = GoalRunnerChildWedgeDiagnosis(
    subtaskId = subtaskId,
    workflowId = workflowId,
    passedChecks = listOf(
      "validation_depth_present",
      "quality_gate_selection_present",
      "review_base_reachable",
      "remediation_base_reachable_or_absent",
      "continuation_outcome_corroborated_or_absent",
      "upstream_output_present",
    ),
  )

  override fun applyChildWedgeRepairs(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    wedgeClasses: List<GoalRunnerWedgeClass>,
    repoRoot: Path,
    dbPathOverride: String?,
  ): GoalRunnerChildRepairApplyResult = GoalRunnerChildRepairApplyResult()
}

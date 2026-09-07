package skillbill.ports.goalrunner.persistence

import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyRequest
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyResult
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeDiagnosis
import skillbill.ports.workflow.WorkflowStateRepository
import java.nio.file.Path

interface GoalRunnerChildRepairRunnerPort {
  fun diagnose(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
  ): GoalRunnerChildWedgeDiagnosis

  fun apply(request: GoalRunnerChildRepairApplyRequest): GoalRunnerChildRepairApplyResult
}

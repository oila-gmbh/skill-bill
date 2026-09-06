package skillbill.ports.goalrunner.persistence

import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyRequest
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyResult
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeDiagnosis
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.model.WorkflowId
import java.nio.file.Path

interface GoalRunnerChildRepairRunnerPort {
  fun diagnose(
    workflowStates: WorkflowStateRepository,
    workflowId: WorkflowId,
    issueKey: IssueKey,
    subtaskId: SubtaskId,
    repoRoot: Path,
  ): GoalRunnerChildWedgeDiagnosis

  fun apply(request: GoalRunnerChildRepairApplyRequest): GoalRunnerChildRepairApplyResult
}

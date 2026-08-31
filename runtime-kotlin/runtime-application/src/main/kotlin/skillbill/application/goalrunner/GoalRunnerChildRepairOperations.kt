package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalRunnerChildRepairApplyRequest
import skillbill.application.goalrunner.model.GoalRunnerChildRepairApplyResult
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowEngine
import java.nio.file.Path
import java.time.Clock

internal const val GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY: String = "goal_child_repair_evidence"

internal class GoalRunnerChildRepairOperations(
  private val engine: WorkflowEngine,
  private val gitOperations: WorkflowGitOperations,
  private val decompositionManifestValidator: DecompositionManifestValidator? = null,
  private val clock: Clock,
) {
  private val wedgeDiagnosis = GoalRunnerChildRepairWedgeDiagnosis(gitOperations)
  private val wedgeApplyLoop = GoalRunnerChildRepairWedgeApplyLoop(
    engine,
    gitOperations,
    wedgeDiagnosis,
    decompositionManifestValidator,
    clock,
  )

  fun diagnose(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
  ): GoalRunnerChildWedgeDiagnosis = wedgeDiagnosis.diagnose(workflowStates, workflowId, issueKey, subtaskId, repoRoot)

  fun apply(request: GoalRunnerChildRepairApplyRequest): GoalRunnerChildRepairApplyResult =
    wedgeApplyLoop.apply(request)
}

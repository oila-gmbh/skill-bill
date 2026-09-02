package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairRunnerPort
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyRequest
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyResult
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeDiagnosis
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import java.nio.file.Path
import java.time.Clock

const val GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY: String = "goal_child_repair_evidence"

@Inject
class GoalRunnerChildRepairOperations(
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val gitOperations: WorkflowGitOperations,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val clock: Clock,
) : GoalRunnerChildRepairRunnerPort {
  private val engine = WorkflowEngine(workflowSnapshotValidator)
  private val wedgeDiagnosis = GoalRunnerChildRepairWedgeDiagnosis(gitOperations)
  private val wedgeApplyLoop = GoalRunnerChildRepairWedgeApplyLoop(
    engine,
    gitOperations,
    wedgeDiagnosis,
    decompositionManifestValidator,
    clock,
  )

  override fun diagnose(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
  ): GoalRunnerChildWedgeDiagnosis = wedgeDiagnosis.diagnose(workflowStates, workflowId, issueKey, subtaskId, repoRoot)

  override fun apply(request: GoalRunnerChildRepairApplyRequest): GoalRunnerChildRepairApplyResult =
    wedgeApplyLoop.apply(request)
}

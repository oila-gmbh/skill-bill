package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairRunnerPort
import skillbill.ports.goalrunner.persistence.PortableReviewBaselinePersistence
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyRequest
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyResult
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairDiagnoseRequest
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeDiagnosis
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import java.time.Clock

const val GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY: String = "goal_child_repair_evidence"

@Inject
class GoalRunnerChildRepairOperations(
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val gitOperations: WorkflowGitOperations,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val portableReviewBaselinePersistence: PortableReviewBaselinePersistence,
  private val clock: Clock,
) : GoalRunnerChildRepairRunnerPort {
  private val engine = WorkflowEngine(workflowSnapshotValidator)
  private val wedgeDiagnosis = GoalRunnerChildRepairWedgeDiagnosis(
    gitOperations,
    portableReviewBaselinePersistence,
  )
  private val wedgeApplyLoop = GoalRunnerChildRepairWedgeApplyLoop(
    engine,
    gitOperations,
    wedgeDiagnosis,
    decompositionManifestValidator,
    portableReviewBaselinePersistence,
    clock,
  )

  override fun diagnose(request: GoalRunnerChildRepairDiagnoseRequest): GoalRunnerChildWedgeDiagnosis =
    wedgeDiagnosis.diagnose(request)

  override fun apply(request: GoalRunnerChildRepairApplyRequest): GoalRunnerChildRepairApplyResult =
    wedgeApplyLoop.apply(request)
}

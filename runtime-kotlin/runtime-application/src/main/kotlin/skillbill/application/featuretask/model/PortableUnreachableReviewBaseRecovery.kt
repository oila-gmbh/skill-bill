package skillbill.application.featuretask.model

import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import java.nio.file.Path

fun interface PortableUnreachableReviewBaseRecovery {
  fun record(command: PortableUnreachableReviewBaseRecoveryCommand)
}

data class PortableUnreachableReviewBaseRecoveryCommand(
  val workflowStates: WorkflowStateRepository,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  val workflowId: String,
  val recoveredBaseline: GoalSubtaskReviewBaseline,
  val repoRoot: Path,
  val engine: WorkflowEngine,
)

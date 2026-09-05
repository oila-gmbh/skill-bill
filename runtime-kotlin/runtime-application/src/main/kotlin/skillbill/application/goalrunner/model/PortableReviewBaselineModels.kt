package skillbill.application.goalrunner.model

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.persistence.PortableReviewBaselinePersistence
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.goal.model.GoalRecoveryAuditEntry
import skillbill.workflow.goal.model.PortableReviewBaseline
import skillbill.workflow.goal.model.PortableReviewBaselineBlockedReason
import java.nio.file.Path

@Inject
data class PortableReviewBaselineRecoveryDeps(
  val persistence: PortableReviewBaselinePersistence,
  val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  val decompositionManifestValidator: DecompositionManifestValidator,
)

data class PortableReviewBaselineValidationRequest(
  val persistence: PortableReviewBaselinePersistence,
  val repoRoot: Path,
  val manifest: DecompositionManifest,
  val subtaskId: Int,
  val expectedWorkflowId: String,
  val expectedRepositoryIdentity: String,
  val expectedBranch: String,
  val gitOperations: WorkflowGitOperations,
  val subtask: DecompositionSubtask? = null,
)

data class PortableReviewBaselineRecoveryRequest(
  val persistence: PortableReviewBaselinePersistence,
  val repoRoot: Path,
  val manifest: DecompositionManifest,
  val subtaskId: Int,
  val workflowId: String,
  val repositoryIdentity: String,
  val goalBranch: String,
  val recoveredBaseline: GoalSubtaskReviewBaseline,
)

data class PortableReviewBaselineWriteRequest(
  val repoRoot: Path,
  val manifest: DecompositionManifest,
  val subtaskId: Int,
  val workflowId: String,
  val repositoryIdentity: String,
  val goalBranch: String,
  val reviewBaseline: GoalSubtaskReviewBaseline,
)

sealed interface PortableReviewBaselineValidation {
  data class Valid(val artifact: PortableReviewBaseline, val reviewBaseline: GoalSubtaskReviewBaseline) :
    PortableReviewBaselineValidation

  data class Blocked(
    val reason: PortableReviewBaselineBlockedReason,
    val detail: String,
    val artifact: PortableReviewBaseline? = null,
  ) : PortableReviewBaselineValidation
}

data class GoalChildOrphanReplacementRequest(
  val state: GoalRunnerManifestState,
  val subtaskId: Int,
  val repoRoot: Path,
  val repositoryIdentity: String,
  val gitOperations: WorkflowGitOperations,
  val codeReviewMode: CodeReviewExecutionMode?,
)

sealed interface GoalChildOrphanReplacementResult {
  data class Replaced(
    val state: GoalRunnerManifestState,
    val sourceWorkflowId: String,
    val replacementWorkflowId: String,
    val reviewBaseline: GoalSubtaskReviewBaseline,
    val auditEntry: GoalRecoveryAuditEntry,
  ) : GoalChildOrphanReplacementResult

  data class Blocked(val reason: PortableReviewBaselineBlockedReason, val detail: String) :
    GoalChildOrphanReplacementResult
}

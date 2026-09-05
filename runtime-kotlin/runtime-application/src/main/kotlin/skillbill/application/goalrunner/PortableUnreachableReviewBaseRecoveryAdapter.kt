package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.PortableUnreachableReviewBaseRecovery
import skillbill.application.featuretask.model.PortableUnreachableReviewBaseRecoveryCommand
import skillbill.application.goalrunner.model.PortableReviewBaselineRecoveryDeps
import skillbill.application.goalrunner.model.PortableReviewBaselineRecoveryRequest

@Inject
class PortableUnreachableReviewBaseRecoveryAdapter(
  private val deps: PortableReviewBaselineRecoveryDeps,
) : PortableUnreachableReviewBaseRecovery {
  override fun record(command: PortableUnreachableReviewBaseRecoveryCommand) {
    val manifest = PortableReviewBaselineRecovery.loadParentManifest(
      command.workflowStates,
      command.continuation,
      deps.decompositionManifestValidator,
    ) ?: return
    if (
      !PortableReviewBaselineRecovery.artifactExists(
        deps.persistence,
        command.repoRoot,
        manifest,
        command.continuation.subtaskId,
      )
    ) {
      return
    }
    val repositoryIdentity = PortableReviewBaselineRecovery.repositoryIdentity(
      command.repoRoot,
      deps.repositoryEnclosingRootPort,
    )
    val auditEntry = PortableReviewBaselineRecovery.recordUnreachableBaseRecovery(
      PortableReviewBaselineRecoveryRequest(
        persistence = deps.persistence,
        repoRoot = command.repoRoot,
        manifest = manifest,
        subtaskId = command.continuation.subtaskId,
        workflowId = command.workflowId,
        repositoryIdentity = repositoryIdentity,
        goalBranch = command.continuation.goalBranch,
        recoveredBaseline = command.recoveredBaseline,
      ),
    )
    command.continuation.parentWorkflowId?.takeIf(String::isNotBlank)?.let { parentWorkflowId ->
      PortableReviewBaselineRecovery.appendParentRecoveryAudit(
        command.engine,
        command.workflowStates,
        parentWorkflowId,
        auditEntry,
      )
    }
  }
}

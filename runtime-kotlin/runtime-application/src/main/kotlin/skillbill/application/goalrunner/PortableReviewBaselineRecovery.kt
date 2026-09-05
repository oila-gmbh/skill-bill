package skillbill.application.goalrunner

import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.decomposition.decodeDecompositionManifestMap
import skillbill.application.goalrunner.model.PortableReviewBaselineRecoveryRequest
import skillbill.application.goalrunner.model.PortableReviewBaselineWriteRequest
import skillbill.application.workflow.model.WorkflowFamily
import skillbill.contracts.JsonSupport
import skillbill.ports.goalrunner.persistence.PortableReviewBaselinePersistence
import skillbill.ports.goalrunner.persistence.model.PortableReviewBaselineRepairContext
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.model.GOAL_RECOVERY_AUDIT_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalRecoveryAuditEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import java.nio.file.Path

object PortableReviewBaselineRecovery {
  const val UNREACHABLE_REVIEW_BASE_REASON: String = "unreachable_review_base"

  fun recordUnreachableBaseRecovery(
    persistence: PortableReviewBaselinePersistence,
    repoRoot: Path,
    portableContext: PortableReviewBaselineRepairContext,
    recoveredBaseline: GoalSubtaskReviewBaseline,
    goalBranch: String,
  ): GoalRecoveryAuditEntry = recordUnreachableBaseRecovery(
    PortableReviewBaselineRecoveryRequest(
      persistence = persistence,
      repoRoot = repoRoot,
      manifest = portableContext.manifest,
      subtaskId = portableContext.subtaskId,
      workflowId = portableContext.workflowId,
      repositoryIdentity = portableContext.repositoryIdentity,
      goalBranch = goalBranch,
      recoveredBaseline = recoveredBaseline,
    ),
  )

  fun recordUnreachableBaseRecovery(request: PortableReviewBaselineRecoveryRequest): GoalRecoveryAuditEntry {
    PortableReviewBaselineWriter(request.persistence).persistBeforeImplementation(
      PortableReviewBaselineWriteRequest(
        repoRoot = request.repoRoot,
        manifest = request.manifest,
        subtaskId = request.subtaskId,
        workflowId = request.workflowId,
        repositoryIdentity = request.repositoryIdentity,
        goalBranch = request.goalBranch,
        reviewBaseline = request.recoveredBaseline,
      ),
    )
    val digest = PortableReviewBaselineMapping.fromReviewBaseline(
      workflowId = request.workflowId,
      repositoryIdentity = request.repositoryIdentity,
      goalBranch = request.goalBranch,
      reviewBaseline = request.recoveredBaseline,
    ).integrityDigest
    return GoalRecoveryAuditEntry(
      sourceWorkflowId = request.workflowId,
      replacementWorkflowId = null,
      artifactDigest = digest,
      selectedBase = request.recoveredBaseline.reviewBaseSha,
      recoveryReason = UNREACHABLE_REVIEW_BASE_REASON,
    )
  }

  fun artifactExists(
    persistence: PortableReviewBaselinePersistence,
    repoRoot: Path,
    manifest: DecompositionManifest,
    subtaskId: Int,
  ): Boolean {
    val path = PortableReviewBaselinePaths.artifactPath(repoRoot, manifest, subtaskId)
    return persistence.read(path) != null
  }

  fun appendParentRecoveryAudit(
    engine: WorkflowEngine,
    workflowStates: WorkflowStateRepository,
    parentWorkflowId: String,
    auditEntry: GoalRecoveryAuditEntry,
  ) {
    val parent = WorkflowFamily.TASK_RUNTIME.get(workflowStates, parentWorkflowId) ?: return
    val artifacts = decodeArtifacts(parent.artifactsJson).toMutableMap()
    val existing = (artifacts[GOAL_RECOVERY_AUDIT_ARTIFACT_KEY] as? List<*>).orEmpty()
    artifacts[GOAL_RECOVERY_AUDIT_ARTIFACT_KEY] = existing + auditEntry.toArtifactMap()
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      parent,
      WorkflowUpdateInput(
        workflowStatus = parent.workflowStatus,
        currentStepId = parent.currentStepId,
        stepUpdates = null,
        artifactsPatch = mapOf(GOAL_RECOVERY_AUDIT_ARTIFACT_KEY to artifacts[GOAL_RECOVERY_AUDIT_ARTIFACT_KEY]),
        sessionId = parent.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.TASK_RUNTIME.save(workflowStates, updated)
  }

  fun loadParentManifest(
    workflowStates: WorkflowStateRepository,
    continuation: FeatureTaskRuntimeGoalContinuationArtifact,
    validator: DecompositionManifestValidator,
  ): DecompositionManifest? {
    val parentWorkflowId = continuation.parentWorkflowId?.takeIf(String::isNotBlank) ?: return null
    val parent = WorkflowFamily.TASK_RUNTIME.get(workflowStates, parentWorkflowId) ?: return null
    val artifacts = decodeArtifacts(parent.artifactsJson)
    return JsonSupport.anyToStringAnyMap(artifacts[DECOMPOSITION_RUNTIME_ARTIFACT_KEY])
      ?.let { decodeDecompositionManifestMap(it, validator, DECOMPOSITION_RUNTIME_ARTIFACT_KEY) }
  }

  fun repositoryIdentity(repoRoot: Path, repositoryEnclosingRootPort: RepositoryEnclosingRootPort): String {
    val canonicalRepository = repositoryEnclosingRootPort.canonicalPath(repoRoot)
    return repositoryEnclosingRootPort.repositoryIdentity(canonicalRepository)
  }
}

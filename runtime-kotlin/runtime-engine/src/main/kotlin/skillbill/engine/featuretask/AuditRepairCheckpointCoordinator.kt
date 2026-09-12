package skillbill.engine.featuretask

import skillbill.model.RepositoryRoot
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.createScopedCheckpoint
import skillbill.ports.workflow.gitops.currentScopedContentFingerprint
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.ports.workflow.gitops.resolveCheckpointRef
import skillbill.ports.workflow.gitops.retainedScopedContentFingerprint
import skillbill.ports.workflow.gitops.updateCheckpointRef
import skillbill.workflow.taskruntime.model.AuditRepairCheckpoint
import skillbill.workflow.taskruntime.model.AuditRepairOutcome
import skillbill.workflow.taskruntime.model.validateAuditRepairPaths
import java.security.MessageDigest

private const val AUDIT_REPAIR_CHECKPOINT_NAMESPACE = "refs/skill-bill/checkpoints/audit-repair"

interface AuditRepairCheckpointCoordinator {
  fun activityFingerprint(): String? = null

  fun verifyCurrent(checkpoint: AuditRepairCheckpoint): Boolean

  fun verifyFingerprint(repositoryFingerprint: String): Boolean = true

  fun verifyRetained(checkpoint: AuditRepairCheckpoint): Boolean

  fun capturePostRepair(
    cycleId: String,
    expectedFingerprint: String,
    outcomes: List<AuditRepairOutcome>,
    checkpointIntent: String? = null,
    ownedPaths: List<String> = emptyList(),
  ): AuditRepairCheckpoint? = null
}

class GitAuditRepairCheckpointCoordinator(
  private val gitOperations: WorkflowGitOperations,
  private val repositoryRoot: RepositoryRoot,
) : AuditRepairCheckpointCoordinator {
  override fun activityFingerprint(): String? = gitOperations.repositoryFingerprint(repositoryRoot.path)
    .takeIf { it is WorkflowGitOperationResult.Ok }?.value?.takeIf(String::isNotBlank)

  override fun verifyFingerprint(repositoryFingerprint: String): Boolean {
    val current = gitOperations.repositoryFingerprint(repositoryRoot.path)
    return current is WorkflowGitOperationResult.Ok && current.value == repositoryFingerprint
  }

  override fun verifyCurrent(checkpoint: AuditRepairCheckpoint): Boolean {
    val current = gitOperations.currentScopedContentFingerprint(repositoryRoot.path, checkpoint.scopedPaths)
    return current is WorkflowGitOperationResult.Ok && current.value == checkpoint.repositoryFingerprint &&
      verifyRetained(checkpoint)
  }

  override fun verifyRetained(checkpoint: AuditRepairCheckpoint): Boolean {
    val retained = gitOperations.retainedScopedContentFingerprint(repositoryRoot.path, checkpoint.checkpointId)
    return retained is WorkflowGitOperationResult.Ok && retained.value == checkpoint.repositoryFingerprint
  }

  override fun capturePostRepair(
    cycleId: String,
    expectedFingerprint: String,
    outcomes: List<AuditRepairOutcome>,
    checkpointIntent: String?,
    ownedPaths: List<String>,
  ): AuditRepairCheckpoint? {
    val paths = (ownedPaths + outcomes.flatMap { it.changedPaths }).distinct().sorted()
    validateAuditRepairPaths(paths)
    if (!verifyFingerprint(expectedFingerprint)) return null
    val fingerprint = gitOperations.currentScopedContentFingerprint(repositoryRoot.path, paths)
    if (fingerprint !is WorkflowGitOperationResult.Ok || fingerprint.value.isBlank()) return null
    val checkpointRef = auditRepairCheckpointRef(cycleId, checkpointIntent)
    val retained = gitOperations.resolveCheckpointRef(
      repositoryRoot.path,
      AUDIT_REPAIR_CHECKPOINT_NAMESPACE,
      checkpointRef,
    )
    if (retained !is WorkflowGitOperationResult.Ok) return null
    return if (retained.value.isNotBlank()) {
      val checkpoint = AuditRepairCheckpoint(retained.value.trim(), fingerprint.value.trim(), paths)
      checkpoint.takeIf(::verifyRetained)
    } else {
      createAndRetain(cycleId, expectedFingerprint, paths, fingerprint.value.trim(), checkpointRef)
    }
  }

  private fun createAndRetain(
    cycleId: String,
    expectedFingerprint: String,
    paths: List<String>,
    fingerprint: String,
    checkpointRef: String,
  ): AuditRepairCheckpoint? {
    val committed = gitOperations.createScopedCheckpoint(repositoryRoot.path, paths, "skill-bill audit repair $cycleId")
    if (committed !is WorkflowGitOperationResult.Ok || committed.value.isBlank()) return null
    val checkpoint = AuditRepairCheckpoint(committed.value.trim(), fingerprint, paths)
    if (!verifyCurrent(checkpoint) || !verifyFingerprint(expectedFingerprint)) return null
    val updated = gitOperations.updateCheckpointRef(
      repositoryRoot.path,
      AUDIT_REPAIR_CHECKPOINT_NAMESPACE,
      checkpointRef,
      checkpoint.checkpointId,
    )
    return checkpoint.takeIf { updated is WorkflowGitOperationResult.Ok }
  }

  private fun auditRepairCheckpointRef(cycleId: String, checkpointIntent: String?): String {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest("$cycleId:${checkpointIntent.orEmpty()}".toByteArray())
    return "$AUDIT_REPAIR_CHECKPOINT_NAMESPACE/" + digest.joinToString("") { "%02x".format(it) }
  }
}

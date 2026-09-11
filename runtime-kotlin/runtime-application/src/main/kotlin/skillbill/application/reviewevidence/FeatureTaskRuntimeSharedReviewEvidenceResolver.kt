package skillbill.application.reviewevidence

import skillbill.application.reviewevidence.model.FeatureTaskRuntimeSharedReviewEvidenceResolved
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDerivation
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolveOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceFileEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceHunkEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedReviewEvidenceReference
import java.nio.file.Path

class FeatureTaskRuntimeSharedReviewEvidenceResolver(
  private val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort,
  private val diffResolver: DiffResolverPort,
) {
  fun resolve(
    repoRoot: Path,
    workflowId: String?,
    checkpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
    consumerPhaseId: String,
  ): FeatureTaskRuntimeSharedReviewEvidenceResolved? {
    if (workflowId.isNullOrBlank() || checkpoint == null) return null
    val resolution = sharedEvidenceResolver.resolve(
      FeatureTaskRuntimeSharedEvidenceRequest(repoRoot, workflowId, checkpoint),
    ) { requested -> derive(repoRoot, requested) }
    val storePath = resolution.storePath?.takeIf(String::isNotBlank) ?: return null
    val reference = FeatureTaskRuntimeSharedReviewEvidenceReference.of(storePath, resolution.artifact)
    return FeatureTaskRuntimeSharedReviewEvidenceResolved(
      reference = reference,
      measurement = FeatureTaskRuntimeSharedEvidenceMeasurement(
        workflowId = workflowId,
        checkpointFingerprint = resolution.artifact.fingerprint,
        consumerPhaseId = consumerPhaseId,
        outcome = resolution.outcome.toMeasurementOutcome(),
        fileIndexCount = resolution.artifact.files.size,
        hunkIndexCount = resolution.artifact.hunks.size,
      ),
    )
  }

  private fun derive(
    repoRoot: Path,
    checkpoint: FeatureTaskRuntimeRepositoryCheckpoint,
  ): FeatureTaskRuntimeSharedEvidenceDerivation {
    val base = checkpoint.baseRef?.takeIf(String::isNotBlank)
    val head = checkpoint.headRef?.takeIf(String::isNotBlank) ?: "HEAD"
    val ownedPaths = checkpoint.workingTreeOwnedPaths.filter(String::isNotBlank)
    val committedArgs = when {
      base == null -> listOf("git", "diff", head)
      ownedPaths.isEmpty() -> listOf("git", "diff", base, head)
      else -> listOf("git", "diff", base)
    }
    val pathArgs = ownedPaths.flatMap { listOf("--", it) }
    val diff = diffResolver.runProcess(committedArgs + pathArgs, repoRoot).orEmpty()
    val evidence = runCatching { ReviewDiffEvidence.parse(diff) }.getOrNull()
    return FeatureTaskRuntimeSharedEvidenceDerivation(
      baseRef = base,
      headRef = head,
      files = evidence?.files.orEmpty().map {
        FeatureTaskRuntimeSharedEvidenceFileEntry(it.path, changeKind(it.oldPath, it.newPath))
      },
      hunks = evidence?.hunks.orEmpty().map {
        FeatureTaskRuntimeSharedEvidenceHunkEntry(it.path, it.content.lineSequence().first().ifBlank { "@@" })
      },
      diffPayload = diff,
    )
  }

  private fun changeKind(oldPath: String?, newPath: String?): String = when {
    oldPath == null -> "added"
    newPath == null -> "deleted"
    oldPath != newPath -> "renamed"
    else -> "modified"
  }
}

private fun FeatureTaskRuntimeSharedEvidenceResolveOutcome.toMeasurementOutcome():
  FeatureTaskRuntimeSharedEvidenceOutcome =
  when (this) {
    FeatureTaskRuntimeSharedEvidenceResolveOutcome.DERIVATION ->
      FeatureTaskRuntimeSharedEvidenceOutcome.DERIVATION
    FeatureTaskRuntimeSharedEvidenceResolveOutcome.REUSE ->
      FeatureTaskRuntimeSharedEvidenceOutcome.REUSE
    FeatureTaskRuntimeSharedEvidenceResolveOutcome.CHECKPOINT_CHANGE_REDERIVATION ->
      FeatureTaskRuntimeSharedEvidenceOutcome.CHECKPOINT_CHANGE_REDERIVATION
  }

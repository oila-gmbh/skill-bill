package skillbill.application.reviewevidence

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

/**
 * One successful shared-evidence resolve: the prompt-visible reference plus the content-free
 * measurement the recorder enqueues for this consumer.
 */
internal data class FeatureTaskRuntimeSharedReviewEvidenceResolved(
  val reference: FeatureTaskRuntimeSharedReviewEvidenceReference,
  val measurement: FeatureTaskRuntimeSharedEvidenceMeasurement,
)

/**
 * Resolves the shared review evidence a phase launch delivers, keyed on the launch's own repository
 * checkpoint fingerprint.
 *
 * Reuse and re-derivation are entirely the port's derive-once semantics: an artifact stored at the
 * requested fingerprint is served without touching the repository, and an absent, unreadable, or
 * fingerprint-mismatched one re-derives. That is why no invalidation concept lives here — re-entry
 * through `audit_gap` or `review_fix` reuses exactly when the freshly resolved fingerprint is
 * unchanged, and remediation that moved the tree changes the fingerprint and therefore re-derives.
 *
 * Resolution lives in the application layer because it touches git and the filesystem; `runtime-domain`
 * receives only the resolved value. A resolution that cannot produce a store path returns null so the
 * non-required declaration is omitted and the launch still succeeds. A fingerprint contradiction from
 * the port is a broken store invariant and propagates — it must never become a silent null omit.
 */
class FeatureTaskRuntimeSharedReviewEvidenceResolver(
  private val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort,
  private val diffResolver: DiffResolverPort,
) {
  internal fun resolve(
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
    val args = if (base == null) listOf("git", "diff", head) else listOf("git", "diff", base, head)
    val diff = diffResolver.runProcess(args, repoRoot).orEmpty()
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

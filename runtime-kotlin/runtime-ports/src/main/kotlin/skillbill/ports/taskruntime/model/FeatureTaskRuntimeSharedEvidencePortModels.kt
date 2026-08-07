package skillbill.ports.taskruntime.model

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceFileEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceHunkEntry
import java.nio.file.Path

/**
 * Address of one resolution: [repoRoot] anchors the repo-local store, and [workflowId] plus the
 * [checkpoint] fingerprint together address the artifact within it.
 */
data class FeatureTaskRuntimeSharedEvidenceRequest(
  val repoRoot: Path,
  val workflowId: String,
  val checkpoint: FeatureTaskRuntimeRepositoryCheckpoint,
) {
  init {
    require(workflowId.isNotBlank()) {
      "FeatureTaskRuntimeSharedEvidenceRequest.workflowId must be non-blank; an unaddressed " +
        "resolution would share one cache slot across every workflow."
    }
  }
}

/**
 * A fresh derivation before it is persisted. The deriver yields the diff payload as bytes; the
 * store owns materializing it and turning it into the artifact's payload reference.
 */
data class FeatureTaskRuntimeSharedEvidenceDerivation(
  val baseRef: String?,
  val headRef: String?,
  val files: List<FeatureTaskRuntimeSharedEvidenceFileEntry>,
  val hunks: List<FeatureTaskRuntimeSharedEvidenceHunkEntry>,
  val diffPayload: String,
)

/**
 * One resolution's outcome: the artifact plus the payload bytes its [artifact] reference addresses.
 *
 * The artifact carries the payload as a reference so a projection never inlines it, but a consumer
 * that rebuilds derived evidence needs the bytes themselves and cannot dereference a store-relative
 * path from outside the store. Returning both keeps the payload's address private to the adapter
 * while still making a fingerprint hit self-sufficient: nothing has to re-traverse the repository to
 * recover what the derivation already produced.
 */
data class FeatureTaskRuntimeSharedEvidenceResolution(
  val artifact: FeatureTaskRuntimeSharedEvidenceArtifact,
  val diffPayload: String,
)

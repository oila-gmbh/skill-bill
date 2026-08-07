package skillbill.ports.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceFileEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceHunkEntry

/**
 * The seam the resolver invokes on any non-hit outcome. It performs the repository traversal the
 * cache exists to avoid, so a resolver that invokes it on a fingerprint hit is a contract
 * violation, not a performance detail.
 */
fun interface FeatureTaskRuntimeSharedEvidenceDeriver {
  fun derive(checkpoint: FeatureTaskRuntimeRepositoryCheckpoint): FeatureTaskRuntimeSharedEvidenceDerivation
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

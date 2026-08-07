package skillbill.ports.taskruntime

import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDerivation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint

/**
 * The seam the resolver invokes on any non-hit outcome. It performs the repository traversal the
 * cache exists to avoid, so a resolver that invokes it on a fingerprint hit is a contract
 * violation, not a performance detail.
 */
fun interface FeatureTaskRuntimeSharedEvidenceDeriver {
  fun derive(checkpoint: FeatureTaskRuntimeRepositoryCheckpoint): FeatureTaskRuntimeSharedEvidenceDerivation
}

package skillbill.ports.taskruntime

import skillbill.error.ReviewHunkEvidenceLocatorMissingError
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceLocatorReadRequest

/**
 * Compose-time dereference of an already-addressed shared-evidence locator.
 *
 * Unlike [FeatureTaskRuntimeSharedEvidenceResolverPort], a miss, unreadable payload, or truncated
 * artifact must not re-derive. The address already exists; failing closed is the only honest outcome.
 */
fun interface FeatureTaskRuntimeSharedEvidenceLocatorReadPort {
  fun readDiffPayload(request: FeatureTaskRuntimeSharedEvidenceLocatorReadRequest): String

  companion object {
    val NONE: FeatureTaskRuntimeSharedEvidenceLocatorReadPort =
      FeatureTaskRuntimeSharedEvidenceLocatorReadPort { request ->
        throw ReviewHunkEvidenceLocatorMissingError(request.storePath)
      }
  }
}

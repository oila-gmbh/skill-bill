package skillbill.application.review

import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import java.util.concurrent.ConcurrentHashMap

class RecordingReviewEvidenceStore {
  private val payloads = ConcurrentHashMap<String, String>()
  val reader = FeatureTaskRuntimeSharedEvidenceLocatorReadPort { request ->
    requireNotNull(payloads[request.storePath])
  }
  val resolver = FeatureTaskRuntimeSharedEvidenceResolverPort { request, deriver ->
    val resolution = FeatureTaskRuntimeSharedEvidenceResolverPort.NONE.resolve(request, deriver)
    val address = "recording-review-evidence/${request.checkpoint.fingerprint}"
    payloads[address] = resolution.diffPayload
    resolution.copy(storePath = address)
  }
}

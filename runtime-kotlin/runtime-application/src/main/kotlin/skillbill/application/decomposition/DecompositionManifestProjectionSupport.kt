package skillbill.application.decomposition

import skillbill.application.workflow.model.DecompositionManifestWriteResult

internal object DecompositionManifestProjectionSupport {
  fun requireWritten(
    result: DecompositionManifestWriteResult?,
    failureDetail: String,
  ): DecompositionManifestWriteResult = checkNotNull(result) { failureDetail }
}

package skillbill.application.decomposition

import skillbill.application.decomposition.model.DecompositionManifestWriteResult

object DecompositionManifestProjectionSupport {
  fun requireWritten(
    result: DecompositionManifestWriteResult?,
    failureDetail: String,
  ): DecompositionManifestWriteResult = checkNotNull(result) { failureDetail }
}

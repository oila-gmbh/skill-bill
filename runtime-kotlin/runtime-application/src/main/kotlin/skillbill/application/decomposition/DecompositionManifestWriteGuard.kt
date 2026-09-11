package skillbill.application.decomposition

import skillbill.application.decomposition.model.DecompositionManifestWriteResult

object DecompositionManifestWriteGuard {
  fun requireWritten(
    result: DecompositionManifestWriteResult?,
    failureDetail: String,
  ): DecompositionManifestWriteResult = checkNotNull(result) { failureDetail }
}

package skillbill.workflow.taskruntime.model

import skillbill.workflow.taskruntime.ReviewDeltaClassifier

enum class ReviewDeltaClassification {
  UNCHANGED,
  BOOKKEEPING_ONLY,
  SEMANTIC,
}

data class ReviewDeltaClassificationResult(
  val contractVersion: String = CONTRACT_VERSION,
  val classification: ReviewDeltaClassification,
  val semanticPaths: List<String>,
  val bookkeepingPaths: List<String>,
) {
  companion object {
    const val CONTRACT_VERSION: String = "0.1"
  }
}

data class ReviewDeltaChange(
  val path: String,
  val runtimeOwnedManifestFields: Set<String> = emptySet(),
) {
  init {
    require(runtimeOwnedManifestFields.all { it in ReviewDeltaClassifier.RUNTIME_OWNED_MANIFEST_FIELDS }) {
      "Review delta contains an ungoverned runtime-owned manifest field."
    }
  }
}

enum class ReviewGenerationDecision {
  RETAIN,
  CREATE_SUCCESSOR,
}

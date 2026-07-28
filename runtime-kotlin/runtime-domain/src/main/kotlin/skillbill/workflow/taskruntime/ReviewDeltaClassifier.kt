package skillbill.workflow.taskruntime

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

class ReviewDeltaClassifier(
  private val bookkeepingPathPrefixes: Set<String> = DEFAULT_BOOKKEEPING_PATH_PREFIXES,
) {
  fun classify(changedPaths: Collection<String>): ReviewDeltaClassificationResult {
    val normalized = changedPaths.map(::normalizePath).filter(String::isNotEmpty).distinct().sorted()
    val (bookkeeping, semantic) = normalized.partition(::isBookkeeping)
    val classification = when {
      normalized.isEmpty() -> ReviewDeltaClassification.UNCHANGED
      semantic.isEmpty() -> ReviewDeltaClassification.BOOKKEEPING_ONLY
      else -> ReviewDeltaClassification.SEMANTIC
    }
    return ReviewDeltaClassificationResult(
      classification = classification,
      semanticPaths = semantic,
      bookkeepingPaths = bookkeeping,
    )
  }

  private fun isBookkeeping(path: String): Boolean =
    bookkeepingPathPrefixes.any { prefix -> path == prefix || path.startsWith("$prefix/") }

  private fun normalizePath(path: String): String =
    path.trim().replace('\\', '/').removePrefix("./")

  companion object {
    val DEFAULT_BOOKKEEPING_PATH_PREFIXES: Set<String> = setOf(
      ".feature-specs",
      ".skill-bill/runtime",
    )
  }
}

enum class ReviewGenerationDecision {
  RETAIN,
  CREATE_SUCCESSOR,
}

object ReviewGenerationPolicy {
  fun decide(classification: ReviewDeltaClassification): ReviewGenerationDecision =
    if (classification == ReviewDeltaClassification.SEMANTIC) {
      ReviewGenerationDecision.CREATE_SUCCESSOR
    } else {
      ReviewGenerationDecision.RETAIN
    }
}

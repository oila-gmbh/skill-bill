package skillbill.workflow.taskruntime

import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewDeltaClassifierTest {
  private val classifier = ReviewDeltaClassifier()

  @Test
  fun `bookkeeping-only changes retain the current generation`() {
    val result = classifier.classify(
      listOf(
        ".feature-specs/SKILL-150/decomposition-manifest.yaml",
        ".skill-bill/runtime/checkpoint.json",
      ),
    )

    assertEquals(ReviewDeltaClassification.BOOKKEEPING_ONLY, result.classification)
    assertEquals(ReviewGenerationDecision.RETAIN, ReviewGenerationPolicy.decide(result.classification))
  }

  @Test
  fun `mixed changes create exactly one semantic successor decision`() {
    val result = classifier.classify(
      listOf(
        ".feature-specs/SKILL-150/decomposition-manifest.yaml",
        "runtime-kotlin/runtime-domain/src/main/kotlin/Review.kt",
        "runtime-kotlin/runtime-domain/src/main/kotlin/Review.kt",
      ),
    )

    assertEquals(ReviewDeltaClassification.SEMANTIC, result.classification)
    assertEquals(
      listOf("runtime-kotlin/runtime-domain/src/main/kotlin/Review.kt"),
      result.semanticPaths,
    )
    assertEquals(ReviewGenerationDecision.CREATE_SUCCESSOR, ReviewGenerationPolicy.decide(result.classification))
  }
}

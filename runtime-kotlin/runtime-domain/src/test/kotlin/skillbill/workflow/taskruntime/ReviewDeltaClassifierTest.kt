package skillbill.workflow.taskruntime

import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewDeltaClassifierTest {
  private val classifier = ReviewDeltaClassifier()

  @Test
  fun `bookkeeping-only changes retain the current generation`() {
    val result = classifier.classify(
      listOf(
        ".feature-specs/runtime-manifest-status/SKILL-150.json",
        ".skill-bill/runtime/checkpoint.json",
      ),
    )

    assertEquals(ReviewDeltaClassification.BOOKKEEPING_ONLY, result.classification)
    assertEquals(ReviewGenerationDecision.RETAIN, ReviewGenerationPolicy.decide(result.classification))
  }

  @Test
  fun `authored feature specs are semantic review input`() {
    val result = classifier.classify(
      listOf(".feature-specs/SKILL-150/decomposition-manifest.yaml"),
    )

    assertEquals(ReviewDeltaClassification.SEMANTIC, result.classification)
  }

  @Test
  fun `runtime owned decomposition manifest fields are bookkeeping but authored fields remain semantic`() {
    val path = ".feature-specs/SKILL-150/decomposition-manifest.yaml"

    assertEquals(
      ReviewDeltaClassification.BOOKKEEPING_ONLY,
      classifier.classifyChanges(
        listOf(ReviewDeltaChange(path, setOf("status", "subtasks.workflow_id"))),
      ).classification,
    )
    assertEquals(
      ReviewDeltaClassification.SEMANTIC,
      classifier.classifyChanges(listOf(ReviewDeltaChange(path))).classification,
    )
  }

  @Test
  fun `ungoverned manifest fields cannot masquerade as bookkeeping`() {
    kotlin.test.assertFailsWith<IllegalArgumentException> {
      ReviewDeltaChange(
        ".feature-specs/SKILL-150/decomposition-manifest.yaml",
        setOf("subtasks.spec_path"),
      )
    }
  }

  @Test
  fun `unified manifest status delta is bookkeeping while authored manifest delta is semantic`() {
    val header =
      "diff --git a/.feature-specs/SKILL-150/decomposition-manifest.yaml " +
        "b/.feature-specs/SKILL-150/decomposition-manifest.yaml\n"
    assertEquals(
      ReviewDeltaClassification.BOOKKEEPING_ONLY,
      classifier.classifyUnifiedDiff(header + "-status: pending\n+status: in_progress\n").classification,
    )
    assertEquals(
      ReviewDeltaClassification.SEMANTIC,
      classifier.classifyUnifiedDiff(header + "-feature_name: old\n+feature_name: new\n").classification,
    )
  }

  @Test
  fun `nested current subtask identity is classified structurally`() {
    val header =
      "diff --git a/.feature-specs/SKILL-150/decomposition-manifest.yaml " +
        "b/.feature-specs/SKILL-150/decomposition-manifest.yaml\n"
    val diff = header +
      " current_subtask_intent:\n" +
      "-  subtask_id: 3\n" +
      "+  subtask_id: 4\n"

    assertEquals(
      ReviewDeltaClassification.BOOKKEEPING_ONLY,
      classifier.classifyUnifiedDiff(diff).classification,
    )
  }

  @Test
  fun `mixed changes create exactly one semantic successor decision`() {
    val result = classifier.classify(
      listOf(
        ".feature-specs/runtime-manifest-status/SKILL-150.json",
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

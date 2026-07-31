package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-004: canonicalization is deterministic and idempotent. Over the whole canned fixture corpus, a
 * second pass must reproduce the first pass's map exactly and apply no further changes (empty
 * diagnostics), so no fixed point is missed. Fixtures are enumerated from the shared source, so a new
 * fixture is covered without editing this test.
 */
class FeatureTaskRuntimeProjectionCanonicalizationIdempotenceTest {
  @Test
  fun `canonicalize equals canonicalize applied twice across every canned fixture`() {
    FeatureTaskRuntimeProjectionCanonicalizationFixtures.ALL.forEachIndexed { index, fixture ->
      val first = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(fixture)
      val second = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(first.canonical)

      assertEquals(first.canonical, second.canonical, "fixture[$index] must reach a canonical fixed point")
      assertTrue(
        second.diagnostics.isEmpty(),
        "fixture[$index] must report no further canonicalizations on the second pass",
      )
    }
  }

  @Test
  fun `an unknown key on a nested closed object reaches a fixed point in one pass`() {
    val produced = mapOf(
      "projection_kind" to "implementation_receipt",
      "tests_executed" to listOf(mapOf("name" to "FooTest", "outcome" to "passed", "duration_ms" to 12)),
      "reconciliation_evidence" to mapOf("reconciled" to true, "evidence" to "  tree at target  ", "extra" to 1),
    )

    val first = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)
    val second = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(first.canonical)

    assertEquals(first.canonical, second.canonical)
    assertTrue(
      second.diagnostics.isEmpty(),
      "the discard must be complete in one pass, leaving nothing for a second",
    )
  }
}

package skillbill.application.featuretask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeContinuationKindTest {
  @Test
  fun `the five kinds are distinguishable on the wire`() {
    val wireValues = FeatureTaskRuntimeContinuationKind.entries.map { it.wireValue }

    assertEquals(5, wireValues.size)
    assertEquals(wireValues.size, wireValues.distinct().size, "continuation kinds must not collide on the wire")
    assertEquals(
      listOf("implementation_continuation", "schema_correction", "process_retry", "crash_resume", "verifier_reentry"),
      wireValues,
    )
  }

  @Test
  fun `every kind round-trips through the ledger detail encoding`() {
    FeatureTaskRuntimeContinuationKind.entries.forEach { kind ->
      val detail = "${FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX}${kind.wireValue}"

      assertEquals(kind, FeatureTaskRuntimeContinuationKind.fromLedgerDetail(detail))
    }
  }

  @Test
  fun `ledger detail keeps the documented category prefix`() {
    FeatureTaskRuntimeContinuationKind.entries.forEach { kind ->
      assertTrue(
        "${FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX}${kind.wireValue}"
          .startsWith(FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX),
      )
    }
  }

  @Test
  fun `an unrelated blocked reason does not decode as a continuation kind`() {
    assertNull(FeatureTaskRuntimeContinuationKind.fromLedgerDetail(null))
    assertNull(FeatureTaskRuntimeContinuationKind.fromLedgerDetail("needs_human: operator decision required"))
    assertNull(FeatureTaskRuntimeContinuationKind.fromLedgerDetail("continuation:not_a_kind"))
  }
}

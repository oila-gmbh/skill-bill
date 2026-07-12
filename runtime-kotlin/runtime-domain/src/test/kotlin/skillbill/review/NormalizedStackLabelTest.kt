package skillbill.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NormalizedStackLabelTest {
  @Test
  fun `mixed kmp labels normalize to kmp while plain kotlin stays kotlin`() {
    val kmp = normalizeStackLabel("KMP/Kotlin")
    assertEquals("kmp", kmp.stack)
    assertFalse(kmp.fallback)
    assertNull(kmp.fallbackReason)
    assertEquals("kmp", normalizeStackLabel("Kotlin Multiplatform (KMP)").stack)
    val kotlin = normalizeStackLabel("Kotlin")
    assertEquals("kotlin", kotlin.stack)
    assertFalse(kotlin.fallback)
    assertNull(kotlin.fallbackReason)
  }

  @Test
  fun `recognized single-token labels keep their slug and unrecognized labels fall back to unknown`() {
    assertEquals("rust", normalizeStackLabel("Rust").stack)
    assertEquals("unknown", normalizeStackLabel("Rust Toolchain").stack)
  }
}

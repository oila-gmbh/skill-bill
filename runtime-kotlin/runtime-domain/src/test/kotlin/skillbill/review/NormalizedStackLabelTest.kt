package skillbill.review

import kotlin.test.Test
import kotlin.test.assertEquals

class NormalizedStackLabelTest {
  @Test
  fun `mixed kmp labels normalize to kmp while plain kotlin stays kotlin`() {
    assertEquals("kmp", normalizeStackLabel("KMP/Kotlin").stack)
    assertEquals("kmp", normalizeStackLabel("Kotlin Multiplatform (KMP)").stack)
    assertEquals("kotlin", normalizeStackLabel("Kotlin").stack)
  }

  @Test
  fun `recognized single-token labels keep their slug and unrecognized labels fall back to unknown`() {
    assertEquals("rust", normalizeStackLabel("Rust").stack)
    assertEquals("unknown", normalizeStackLabel("Rust Toolchain").stack)
  }
}

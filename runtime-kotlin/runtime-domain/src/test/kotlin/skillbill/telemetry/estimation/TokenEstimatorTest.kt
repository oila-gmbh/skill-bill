package skillbill.telemetry.estimation

import kotlin.test.Test
import kotlin.test.assertEquals

class TokenEstimatorTest {
  @Test
  fun `empty string estimates zero tokens`() {
    assertEquals(0, estimateTokens(""))
  }

  @Test
  fun `four ASCII bytes estimate one token`() {
    assertEquals(1, estimateTokens("abcd"))
  }

  @Test
  fun `five ASCII bytes estimate two tokens`() {
    assertEquals(2, estimateTokens("abcde"))
  }

  @Test
  fun `single euro sign (3 UTF-8 bytes) estimates one token`() {
    assertEquals(1, estimateTokens("€"))
  }

  @Test
  fun `five euro signs (15 UTF-8 bytes) estimate four tokens`() {
    assertEquals(4, estimateTokens("€€€€€"))
  }

  @Test
  fun `single musical symbol G-clef (4 UTF-8 bytes) estimates one token`() {
    assertEquals(1, estimateTokens("𝄞"))
  }

  @Test
  fun `four G-clef symbols (16 UTF-8 bytes) estimate four tokens`() {
    assertEquals(4, estimateTokens("𝄞𝄞𝄞𝄞"))
  }

  @Test
  fun `five G-clef symbols (20 UTF-8 bytes) estimate five tokens`() {
    assertEquals(5, estimateTokens("𝄞𝄞𝄞𝄞𝄞"))
  }
}

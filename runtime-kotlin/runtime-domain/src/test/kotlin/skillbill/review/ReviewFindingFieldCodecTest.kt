package skillbill.review

import skillbill.review.model.ReviewFindingCitation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReviewFindingFieldCodecTest {
  @Test
  fun `decodeCitations omits missing zero negative and non-numeric lines with diagnostics`() {
    val decoded = ReviewFindingFieldCodec.decodeCitations(
      listOf(
        mapOf("path" to "src/A.kt", "line" to 0),
        mapOf("path" to "src/B.kt", "line" to -3),
        mapOf("path" to "src/C.kt", "line" to "abc"),
        mapOf("path" to "src/D.kt"),
      ),
    )
    assertTrue(decoded.citations.isEmpty())
    assertEquals(
      listOf("non_positive_line", "non_positive_line", "non_numeric_line", "missing_line"),
      decoded.diagnostics.map { it.reason },
    )
    assertEquals(listOf(0, 1, 2, 3), decoded.diagnostics.map { it.citationIndex })
  }

  @Test
  fun `decodeCitations keeps valid citations alongside malformed entries`() {
    val decoded = ReviewFindingFieldCodec.decodeCitations(
      listOf(
        mapOf("path" to "src/Valid.kt", "line" to 12),
        mapOf("path" to "src/Invalid.kt", "line" to 0),
      ),
    )
    assertEquals(listOf(ReviewFindingCitation("src/Valid.kt", 12)), decoded.citations)
    assertEquals(1, decoded.diagnostics.size)
    assertEquals("non_positive_line", decoded.diagnostics.single().reason)
    assertEquals("src/Invalid.kt", decoded.diagnostics.single().path)
  }

  @Test
  fun `citationsOf still rejects absolute and traversal citation paths`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewFindingFieldCodec.citationsOf(listOf(mapOf("path" to "/etc/passwd", "line" to 1)))
    }
    assertFailsWith<IllegalArgumentException> {
      ReviewFindingFieldCodec.citationsOf(listOf(mapOf("path" to "../secret.kt", "line" to 1)))
    }
  }

  @Test
  fun `citationsOf accepts positive numeric string lines`() {
    assertEquals(
      listOf(ReviewFindingCitation("src/A.kt", 7)),
      ReviewFindingFieldCodec.citationsOf(listOf(mapOf("path" to "src/A.kt", "line" to "7"))),
    )
  }
}

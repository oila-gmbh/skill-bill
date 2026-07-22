package skillbill.review.plan

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewPathMatcherTest {
  @Test
  fun `internal wildcard and directory signals use the same normalized semantics`() {
    assertTrue(ReviewPathMatcher.matches("src/commonMain/kotlin/Screen.kt", "src/*/kotlin/*.kt"))
    assertTrue(ReviewPathMatcher.matches("src/commonMain/kotlin/Screen.kt", "/commonMain/"))
    assertFalse(ReviewPathMatcher.matches("src/commonMain/kotlin/Screen.kt", "src/androidMain/**"))
  }
}

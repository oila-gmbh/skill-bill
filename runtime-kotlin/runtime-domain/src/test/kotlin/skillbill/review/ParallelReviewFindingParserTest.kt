package skillbill.review

import kotlin.test.Test
import kotlin.test.assertEquals

class ParallelReviewFindingParserTest {
  @Test
  fun `parser retains explicit inline specialist identity`() {
    val finding = ParallelReviewFindingParser.parse(
      "[F-001] Major | High | specialist=bill-kotlin-code-review-security | src/Auth.kt:12 | missing check",
    ).single()

    assertEquals("bill-kotlin-code-review-security", finding.specialistSkillName)
    assertEquals("src/Auth.kt:12", finding.location)
    assertEquals("missing check", finding.description)
  }

  @Test
  fun `legacy delegated finding remains parseable without specialist identity`() {
    val finding = ParallelReviewFindingParser.parse(
      "[F-001] Minor | Medium | src/Main.kt:2 | stale branch",
    ).single()

    assertEquals(null, finding.specialistSkillName)
  }
}

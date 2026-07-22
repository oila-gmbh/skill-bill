@file:Suppress("MaxLineLength")

package skillbill.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParallelReviewFindingParserTest {
  @Test
  fun `parser retains explicit inline specialist identity`() {
    val finding = ParallelReviewFindingParser.parse(
      "[F-001] Major | High | specialist=bill-kotlin-code-review-security | path=\"src/Auth.kt\" | line=12 | missing check",
    ).single()

    assertEquals("bill-kotlin-code-review-security", finding.specialistSkillName)
    assertEquals("src/Auth.kt:12", finding.location)
    assertEquals("src/Auth.kt", finding.repositoryPath)
    assertEquals("missing check", finding.description)
  }

  @Test
  fun `structured delegated finding remains parseable without specialist identity`() {
    val finding = ParallelReviewFindingParser.parse(
      "[F-001] Minor | Medium | path=\"src/Main.kt\" | line=2 | stale branch",
    ).single()

    assertEquals(null, finding.specialistSkillName)
  }

  @Test
  fun `structured path round trips punctuation backslash and controls`() {
    val finding = ParallelReviewFindingParser.parse(
      "[F-001] Minor | High | path=\"A|b\\\\c\\t.kt\" | line=7 | exact owner",
    ).single()
    assertEquals("A|b\\c\t.kt", finding.repositoryPath)
  }

  @Test
  fun `parser accepts only positive representable line numbers`() {
    val inputs = listOf("0", "-1", "1", "2147483648")
    val parsed = inputs.map { line ->
      ParallelReviewFindingParser.parse(
        "[F-001] Major | High | path=\"src/Auth.kt\" | line=$line | bounded line",
      )
    }

    assertTrue(parsed[0].isEmpty())
    assertTrue(parsed[1].isEmpty())
    assertEquals(1, parsed[2].single().line)
    assertTrue(parsed[3].isEmpty())
  }
}

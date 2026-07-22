@file:Suppress("MaxLineLength")

package skillbill.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParallelReviewFindingParserTest {
  @Test
  fun `parser retains explicit inline specialist identity`() {
    val finding = ParallelReviewFindingParser.parse(
      "[F-001] Major | High | specialist=bill-kotlin-code-review-security | " +
        "path=\"src/Auth.kt\" | line=12 | missing check",
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
  fun `documented file line finding remains parseable`() {
    val finding = ParallelReviewFindingParser.parse(
      "- [F-001] Blocker | High | src/main/App.kt:42 | compliant worker output",
    ).single()

    assertEquals("src/main/App.kt:42", finding.location)
    assertEquals("src/main/App.kt", finding.repositoryPath)
    assertEquals(42, finding.line)
    assertEquals("compliant worker output", finding.description)
  }

  @Test
  fun `documented file line finding retains specialist identity`() {
    val finding = ParallelReviewFindingParser.parse(
      "[F-001] Major | Medium | specialist=bill-kotlin-code-review-testing | src/Test.kt:9 | weak assertion",
    ).single()

    assertEquals("bill-kotlin-code-review-testing", finding.specialistSkillName)
    assertEquals("src/Test.kt", finding.repositoryPath)
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

  @Test
  fun `documented file line format accepts only positive representable line numbers`() {
    val inputs = listOf("0", "-1", "1", "2147483648")
    val parsed = inputs.map { line ->
      ParallelReviewFindingParser.parse(
        "[F-001] Major | High | src/Auth.kt:$line | bounded line",
      )
    }

    assertTrue(parsed[0].isEmpty())
    assertTrue(parsed[1].isEmpty())
    assertEquals(1, parsed[2].single().line)
    assertTrue(parsed[3].isEmpty())
  }

  @Test
  fun `parser preserves finding order across structured and documented formats`() {
    val findings = ParallelReviewFindingParser.parse(
      """
      - [F-001] Major | High | src/Legacy.kt:3 | legacy contract
      - [F-002] Minor | Low | path="src/Structured.kt" | line=4 | structured contract
      """.trimIndent(),
    )

    assertEquals(listOf("src/Legacy.kt", "src/Structured.kt"), findings.map { it.repositoryPath })
  }
}

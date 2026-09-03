package skillbill.contracts.issuekey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IssueKeyShapeTest {
  @Test
  fun `effective issue-key bounds load from the packaged schema document`() {
    val document = IssueKeyShape::class.java.classLoader
      .getResourceAsStream(IssueKeyShape.RESOURCE_PATH)
      ?.use { stream -> stream.readBytes().decodeToString() }
      ?: error("issue-key schema is missing from the classpath")
    val parsed = IssueKeyShape.parse(document)
    assertEquals(parsed.maxLength, IssueKeyShape.maxLength)
    assertEquals(parsed.pattern, IssueKeyShape.jsonSchemaPattern)
    assertEquals(parsed.maxLength, MAX_ISSUE_KEY_LENGTH)
  }

  @Test
  fun `digit-leading and free-form keys are well formed`() {
    assertTrue(isWellFormedIssueKey("0AC-11"))
    assertTrue(isWellFormedIssueKey(" backlog-item "))
    assertEquals("0ac-11", normalizeRequiredIssueKey(" 0ac-11 "))
  }

  @Test
  fun `blank control-bearing and oversized keys are rejected`() {
    assertFalse(isWellFormedIssueKey("   "))
    assertFalse(isWellFormedIssueKey("SKILL-129\nspoofed"))
    assertFalse(isWellFormedIssueKey("S".repeat(MAX_ISSUE_KEY_LENGTH + 1)))
  }

  @Test
  fun `directory names split tracker-style keys from the feature slug`() {
    assertEquals("0AC-11" to "be-sessions", issueAndFeature("0AC-11-be-sessions"))
    assertEquals("SKILL-129" to "super-optimized-code-review", issueAndFeature("SKILL-129-super-optimized-code-review"))
    assertEquals("TICKET" to "my-feature", issueAndFeature("TICKET-my-feature"))
  }

  @Test
  fun `branch names yield the same tracker-style key the directory parser would`() {
    assertEquals("0AC-11", issueKeyFromBranch("sermilionrestless/0ac-11-be-sessions-show-locationcountry-as-zz"))
    assertEquals("SKILL-129", issueKeyFromBranch("feat/SKILL-129-super-optimized-code-review"))
  }
}

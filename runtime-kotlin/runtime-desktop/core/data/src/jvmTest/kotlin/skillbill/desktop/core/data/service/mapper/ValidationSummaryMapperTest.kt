package skillbill.desktop.core.data.service.mapper

import skillbill.desktop.core.domain.model.ValidationRunState
import skillbill.desktop.core.domain.model.ValidationSeverity
import skillbill.ports.scaffold.repo.model.ScaffoldValidateResult
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-52.2 subtask 5 (review-fix F-003) — unit coverage for
 * [toSelectedValidationSummary]. This mapper encodes the pass/fail business
 * policy (anything other than `"pass"` ⇒ FAILED) and tolerates malformed
 * `issues` payload entries by filtering to strings. The behavior is the
 * source of truth for the dock-bar validation state of a selected skill, so
 * each branch is pinned here.
 */
class ValidationSummaryMapperTest {
  private val root: Path = Path.of("/repo")

  @Test
  fun `status pass maps to PASSED state`() {
    val summary = scaffoldValidateResult(status = "pass").toSelectedValidationSummary(root)

    assertEquals(ValidationRunState.PASSED, summary.state)
    assertEquals(emptyList(), summary.issues)
  }

  @Test
  fun `status fail maps to FAILED state`() {
    val summary = scaffoldValidateResult(status = "fail").toSelectedValidationSummary(root)

    assertEquals(ValidationRunState.FAILED, summary.state)
  }

  @Test
  fun `any non-pass status maps to FAILED state - current behavior pinned`() {
    // Only the literal string "pass" maps to PASSED. Anything else — including
    // unexpected `"warning"`, `"error"`, empty string, or arbitrary noise — is
    // collapsed to FAILED. Lock this so a future refactor that adds a
    // "warning" tier without updating this mapper is caught.
    listOf("warning", "error", "unknown", "PASS", "").forEach { status ->
      val summary = scaffoldValidateResult(status = status).toSelectedValidationSummary(root)
      assertEquals(
        ValidationRunState.FAILED,
        summary.state,
        "Status `$status` must currently map to FAILED.",
      )
    }
  }

  @Test
  fun `mixed-type issues entries are filtered to strings without throwing`() {
    // `RepoValidationIssue.fromRawIssue` only handles strings. The mapper must
    // not throw on a payload whose issue list contains non-string entries
    // (numbers, nested maps, nulls). Those entries are dropped; surviving
    // strings are decoded.
    val summary = scaffoldValidateResult(
      status = "fail",
      issues = listOf(
        "skills/alpha/SKILL.md: missing frontmatter `description`",
        42,
        mapOf("code" to "X"),
        null,
        "skills/beta/SKILL.md: unbalanced fenced block",
      ),
    ).toSelectedValidationSummary(root)

    assertEquals(ValidationRunState.FAILED, summary.state)
    assertEquals(2, summary.issues.size)
    assertEquals("missing frontmatter `description`", summary.issues[0].message)
    assertEquals("unbalanced fenced block", summary.issues[1].message)
  }

  @Test
  fun `empty issues list yields no issues`() {
    val summary = scaffoldValidateResult(status = "fail", issues = emptyList<Any?>())
      .toSelectedValidationSummary(root)

    assertEquals(ValidationRunState.FAILED, summary.state)
    assertEquals(emptyList(), summary.issues)
  }

  @Test
  fun `missing issues key yields no issues`() {
    val summary = ScaffoldValidateResult(
      status = "fail",
      payload = mapOf("status" to "fail"),
    ).toSelectedValidationSummary(root)

    assertEquals(ValidationRunState.FAILED, summary.state)
    assertEquals(emptyList(), summary.issues)
  }

  @Test
  fun `canonical issue string is decoded into severity message and source path`() {
    // `RepoValidationIssue.fromRawIssue` recognises `"<source_path>: <message>"`
    // when `<source_path>` is non-blank and contains no spaces; everything
    // else falls back to message-only. Exercise both branches end-to-end so a
    // future parser tweak is caught.
    val summary = scaffoldValidateResult(
      status = "fail",
      issues = listOf(
        "skills/alpha/SKILL.md: missing frontmatter `description`",
        "generic problem without a colon-prefixed source path",
      ),
    ).toSelectedValidationSummary(root)

    assertEquals(2, summary.issues.size)
    assertEquals(ValidationSeverity.ERROR, summary.issues[0].severity)
    assertEquals("missing frontmatter `description`", summary.issues[0].message)
    assertEquals("skills/alpha/SKILL.md", summary.issues[0].sourcePath)
    assertEquals(ValidationSeverity.ERROR, summary.issues[1].severity)
    assertEquals("generic problem without a colon-prefixed source path", summary.issues[1].message)
    assertTrue(
      summary.issues[1].sourcePath.isNullOrBlank(),
      "Fallback issue parse should not invent a source path.",
    )
  }

  private fun scaffoldValidateResult(status: String, issues: List<Any?>? = null): ScaffoldValidateResult {
    val payload: Map<String, Any?> = if (issues == null) {
      mapOf("status" to status)
    } else {
      mapOf("status" to status, "issues" to issues)
    }
    return ScaffoldValidateResult(status = status, payload = payload)
  }
}

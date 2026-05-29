package skillbill.desktop.core.data.service.mapper

import skillbill.desktop.core.domain.model.ValidationRunState
import skillbill.desktop.core.domain.model.ValidationSeverity
import skillbill.ports.scaffold.repo.model.ScaffoldValidateResult
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-52.3 subtask 3 — unit coverage for [toSelectedValidationSummary]. This mapper
 * encodes the pass/fail business policy (anything other than `"pass"` ⇒ FAILED) and
 * decodes each typed `issues: List<String>` entry through `RepoValidationIssue`. The
 * behavior is the source of truth for the dock-bar validation state of a selected
 * skill, so each branch is pinned here.
 *
 * SKILL-52.3 subtask 3 retired the `@OpenBoundaryMap` `payload` field; `issues` is now a
 * typed `List<String>`, so the historical mixed-type filtering branch is structurally
 * impossible and was removed.
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
  fun `each typed issue string is decoded in order`() {
    val summary = scaffoldValidateResult(
      status = "fail",
      issues = listOf(
        "skills/alpha/SKILL.md: missing frontmatter `description`",
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
    val summary = scaffoldValidateResult(status = "fail", issues = emptyList())
      .toSelectedValidationSummary(root)

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

  private fun scaffoldValidateResult(status: String, issues: List<String> = emptyList()): ScaffoldValidateResult =
    ScaffoldValidateResult(
      repoRoot = "/repo",
      mode = "selected",
      status = status,
      issues = issues,
      skillNames = listOf("alpha"),
      suggestedCommands = emptyList(),
    )
}

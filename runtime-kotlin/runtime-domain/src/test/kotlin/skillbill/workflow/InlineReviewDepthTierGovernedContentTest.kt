package skillbill.workflow

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SKILL-142 AC-001 to AC-004 and AC-008: the governed review and goal contracts describe `inline` as
 * a distinct light depth tier and stop forcing the complete base-to-current delta on the reserved
 * remediation pass.
 */
class InlineReviewDepthTierGovernedContentTest {
  // Governed prose is hard-wrapped, so assert against a whitespace-normalized view; a reflow must not
  // break a contract assertion.
  private fun governedText(path: String): String =
    Files.readString(governedRepoRoot().resolve(path)).replace(Regex("\\s+"), " ")

  private val codeReview: String = governedText("skills/bill-code-review/content.md")

  private val featureGoal: String = governedText("skills/bill-feature-goal/content.md")

  @Test
  fun `inline is a distinct depth tier, not a topology variant of delegated`() {
    assertTrue(codeReview.contains("two review depths, not two ways to execute the same"))
    assertTrue(codeReview.contains("no specialist workers"))
    assertTrue(codeReview.contains("bounded budget"))
    assertTrue(codeReview.contains("explicit checklist"))
    assertTrue(codeReview.contains("reduced depth"))
  }

  @Test
  fun `the equivalent-coverage claims are gone`() {
    assertFalse(
      codeReview.contains("regardless of size or risk"),
      "The inline tier must no longer claim the complete routed review regardless of size or risk.",
    )
    assertFalse(
      codeReview.contains("required coverage"),
      "The inline tier must no longer claim required coverage.",
    )
    assertTrue(codeReview.contains("never present it as equivalent to a delegated result"))
  }

  @Test
  fun `the finding bar is inherited unchanged rather than lowered`() {
    assertTrue(codeReview.contains("Depth is the only thing the light tier lowers."))
    listOf(
      "severity vocabulary",
      "finding admission gate",
      "evidence and observable-consequence requirements",
      "F-XXX risk register format",
      "telemetry",
    ).forEach { inherited ->
      assertTrue(codeReview.contains(inherited), "The inherited-unchanged paragraph must keep '$inherited'.")
    }
  }

  @Test
  fun `delegated stays the full-depth default and loud-fails unlaunchable workers`() {
    assertTrue(codeReview.contains("`delegated` is the full-depth review and the default."))
    assertTrue(codeReview.contains("blocks loudly; it never degrades to inline"))
  }

  @Test
  fun `auto resolves by pass number and an explicit tier always overrides it`() {
    assertTrue(codeReview.contains("pass one resolves to `delegated`"))
    assertTrue(codeReview.contains("every later pass resolves to `inline`"))
    assertTrue(codeReview.contains("explicit `inline` or `delegated` always overrides it"))
  }

  @Test
  fun `the goal contract stops forcing the complete delta on the reserved remediation pass`() {
    assertFalse(
      featureGoal.contains("Every child review, including repair and audit-driven re-entry, reviews the"),
      "The goal contract must no longer override context:feature-remediation for every child review.",
    )
    assertTrue(featureGoal.contains("Pass one reviews the complete base-to-current delta"))
    assertTrue(featureGoal.contains("bounded to the remediation delta via `context:feature-remediation`"))
  }

  @Test
  fun `pass one keeps the immutable baseline authority verbatim`() {
    assertTrue(featureGoal.contains("immutable `review_base_sha`"))
    assertTrue(featureGoal.contains("baseline untracked inventory"))
    assertTrue(featureGoal.contains("current untracked paths - baseline untracked inventory"))
  }

  @Test
  fun `the governed text carries no consecutive blank runs`() {
    listOf("skills/bill-code-review/content.md", "skills/bill-feature-goal/content.md").forEach { path ->
      val text = Files.readString(governedRepoRoot().resolve(path))
      assertFalse(
        text.contains("\n\n\n"),
        "$path must not carry consecutive blank lines; the renderer emits them verbatim.",
      )
    }
  }
}

private fun governedRepoRoot(): Path {
  var current = Path.of("").toAbsolutePath().normalize()
  while (current.parent != null) {
    if (
      Files.isRegularFile(current.resolve("runtime-kotlin/settings.gradle.kts")) &&
      Files.isDirectory(current.resolve("orchestration/contracts"))
    ) {
      return current
    }
    current = current.parent
  }
  error("Could not locate skill-bill repo root from ${Path.of("").toAbsolutePath().normalize()}")
}

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

  private val inlineWorker: String = governedText("skills/bill-code-review-inline/content.md")

  private val featureGoal: String = governedText("skills/bill-feature-goal/content.md")

  @Test
  fun `inline is a distinct depth tier, not a topology variant of delegated`() {
    assertTrue(codeReview.contains("two review depths, not two ways to execute the same"))
    assertTrue(codeReview.contains("no per-area specialist workers"))
    assertTrue(codeReview.contains("bounded budget"))
    assertTrue(codeReview.contains("reduced depth"))
  }

  @Test
  fun `inline traverses the delta once with every area held simultaneously`() {
    assertTrue(codeReview.contains("one combined"))
    assertTrue(codeReview.contains("traverses the delta exactly once"))
    assertTrue(codeReview.contains("never re-walk the same delta once per area"))
    assertTrue(codeReview.contains("holding all areas in mind simultaneously"))
    assertTrue(inlineWorker.contains("One pass over the delta. Never re-walk it per area."))
    assertTrue(inlineWorker.contains("traverse the delta exactly once"))
    assertTrue(inlineWorker.contains("not an iteration order"))
  }

  @Test
  fun `inline runs as the declared native agent the driver launches`() {
    assertTrue(codeReview.contains("one review subagent launched by the"))
    assertTrue(codeReview.contains("`bill-code-review-inline` native agent"))
    assertTrue(codeReview.contains("skill-bill code-review"))
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
    assertTrue(codeReview.contains("Never present it as equivalent to a delegated result"))
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
  fun `inline is the default and delegated stays explicit-only while loud-failing unlaunchable workers`() {
    assertTrue(codeReview.contains("Omission means `mode:inline`."))
    assertTrue(codeReview.contains("`inline` is the default depth."))
    assertTrue(codeReview.contains("experimental full-depth tier"))
    assertTrue(codeReview.contains("Neither an omitted argument"))
    assertTrue(codeReview.contains("blocks loudly; it never degrades to inline"))
  }

  @Test
  fun `auto resolves inline on every pass and never reaches delegated`() {
    assertTrue(codeReview.contains("`auto` resolves to `inline` everywhere"))
    assertTrue(codeReview.contains("`auto` never reaches the"))
  }

  @Test
  fun `the entry contract invokes the driver instead of orchestrating lanes`() {
    assertTrue(codeReview.contains("skill-bill code-review"))
    assertTrue(codeReview.contains("parallel: arg > code_review_parallel_agent"))
    assertFalse(codeReview.contains("mktemp"))
    assertFalse(codeReview.contains("claude -p"))
    assertFalse(codeReview.contains("stdin-pipe"))
    assertFalse(codeReview.contains("## Scope Resolution"))
    assertFalse(codeReview.contains("skill-bill code-review-merge"))
  }

  @Test
  fun `the goal contract stops forcing the complete delta on the reserved remediation pass`() {
    assertFalse(
      featureGoal.contains("Every child review, including repair and audit-driven re-entry, reviews the"),
      "The goal contract must no longer override context:feature-remediation for every child review.",
    )
    assertTrue(featureGoal.contains("Pass one reviews the complete base-to-current delta"))
    assertTrue(
      featureGoal.contains(
        "every later pass runs inline against the remediation delta via `context:feature-remediation`",
      ),
    )
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

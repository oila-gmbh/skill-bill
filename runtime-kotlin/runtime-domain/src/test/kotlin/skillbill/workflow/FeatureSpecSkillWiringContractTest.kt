package skillbill.workflow

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FeatureSpecSkillWiringContractTest {
  @Test
  fun `bill feature content routes through spec preparation before execution`() {
    val content = Files.readString(repoRootFromTest().resolve("skills/bill-feature/content.md"))

    assertContains(content, "name: bill-feature")
    assertContains(content, "Always invoke `bill-feature-spec` first")
    assertContains(content, "Treat its selected mode as authoritative for dispatch")
    assertContains(content, "## Direct Dispatch When Governed Artifacts Exist")
    assertContains(content, "For `single_spec` output")
    // SKILL-102: task and goal execution dispatch by reading the internal-skill sidecars, never
    // via the Skill tool.
    assertContains(content, "Read the file `bill-feature-task.md` located in this skill's own installed directory")
    assertContains(content, "For `decomposed` output")
    assertContains(content, "Read the file `bill-feature-goal.md` located in this skill's own installed directory")
    assertContains(content, "Do not ask an extra confirmation before dispatching to the goal sidecar")
  }

  @Test
  fun `bill feature spec content defines governed intake and modes`() {
    val content = Files.readString(repoRootFromTest().resolve("skills/bill-feature-spec/content.md"))

    assertContains(content, "name: bill-feature-spec")
    assertContains(content, "If the issue key is missing, stop and ask for it.")
    assertContains(content, "single_spec")
    assertContains(content, "decomposed")
    assertContains(
      content,
      "Do not fork logic between `bill-feature-spec`, `bill-feature-task`, and `bill-feature-goal`.",
    )
  }

  @Test
  fun `bill feature task prose content routes decomposition through shared preparation path`() {
    val content = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-prose/content.md"))

    assertContains(content, "## Shared Feature-Spec Preparation Path")
    assertContains(content, "invoke the shared feature-spec preparation path")
  }

  @Test
  fun `issue keyed prose and verify workflow openings forward only normalized issue keys`() {
    val proseContent = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-prose/content.md"))
    val verifyContent = Files.readString(repoRootFromTest().resolve("skills/bill-feature-verify/content.md"))

    assertContains(proseContent, "issue_key: <normalized issue key>")
    assertContains(verifyContent, "issue_key: <normalized issue key>")
    assertContains(verifyContent, "otherwise omit the field")
    assertContains(verifyContent, "rather than deriving one from presentation data, workflow ids, or free text")
  }

  @Test
  fun `bill feature goal content reuses shared preparation and keeps goal runner consumer only`() {
    val content = Files.readString(repoRootFromTest().resolve("skills/bill-feature-goal/content.md"))
    val featureSpecContent = Files.readString(repoRootFromTest().resolve("skills/bill-feature-spec/content.md"))

    assertContains(content, "invoke `bill-feature-spec` in this session")
    assertContains(content, "`bill-feature-goal` is the trigger surface for decomposed-goal orchestration")
    assertContains(content, "`skill-bill goal <issue_key>` remains consumer-only")
    assertContains(featureSpecContent, "`skill-bill goal <issue_key>` is consumer-only")
    assertContains(content, "Ask one confirmation question")
    assertEquals(1, countOccurrences(content, "Ask one confirmation question"))
  }

  @Test
  fun `review mode source contracts reject invalid selection and preserve the selected mode through prose goals`() {
    val feature = Files.readString(repoRootFromTest().resolve("skills/bill-feature/content.md"))
    val task = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task/content.md"))
    val goal = Files.readString(repoRootFromTest().resolve("skills/bill-feature-goal/content.md"))
    val review = Files.readString(repoRootFromTest().resolve("skills/bill-code-review/content.md"))
    val runtime = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-runtime/content.md"))
    val prose = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-prose/content.md"))
    val subtaskRunner = Files.readString(
      repoRootFromTest().resolve("skills/bill-feature-task-subtask-runner/content.md"),
    )
    val nativeAgents = Files.readString(
      repoRootFromTest().resolve("skills/bill-feature-task-prose/native-agents/agents.yaml"),
    )

    assertContains(feature, "zero or one `code-review:auto`, `code-review:inline`, or")
    assertContains(feature, "Reject a malformed, unknown, repeated, or conflicting")
    assertContains(feature, "When omitted, do not synthesize `code-review:auto`")
    assertContains(feature, "omitting the `code-review:` token when the caller did not provide it")
    assertContains(task, "the requested code-review selection, showing `auto (default)` when omitted")
    assertEquals(1, countOccurrences(task, "Ask exactly one confirmation question"))
    assertContains(task, "not repeat intake or present another confirmation gate")
    assertContains(runtime, "The `bill-feature-task` router has already rejected invalid review-selection")
    assertContains(runtime, "Do not reparse, default, or\nchange `code-review:<selected-mode>`")
    assertFalse(runtime.contains("## Single Confirmation Gate"))
    assertContains(prose, "Obtain the normalized `code-review:auto|inline|delegated` selection")
    assertContains(prose, "durable goal and child workflow state supply the immutable")
    assertContains(prose, "This sidecar must not reparse the token, present another gate")
    assertContains(prose, "The router's confirmation is the only gate")
    assertFalse(prose.contains("Then ask: **Confirm or adjust the above before I plan.**"))
    assertContains(subtaskRunner, "bill-code-review execution-mode:<code_review_mode>")
    assertFalse(subtaskRunner.contains("bill-code-review execution-mode:code_review_mode"))
    assertContains(goal, "selected mode is immutable for the parent and every child")
    assertContains(review, "`delegated` always runs the normal routed delegated path")
    assertContains(review, "is allowed only after every shared eligibility condition passes")
    assertContains(review, "Do not pass `parallel:` into lane 2")
    assertContains(nativeAgents, "Code-review execution mode: {code_review_mode}")
    assertContains(nativeAgents, "Parallel review agent: {parallel_review_agent}")
    assertContains(nativeAgents, "Immutable review base SHA: {review_base_sha}")
    assertContains(nativeAgents, "Baseline untracked inventory: {baseline_untracked_paths}")
    assertContains(nativeAgents, "Completed review passes: {completed_review_pass_count}")
    assertContains(nativeAgents, "Reserved review pass: {reserved_review_pass_number}")
    assertContains(nativeAgents, "Review cap disposition: {review_cap_disposition}")
  }

  @Test
  fun `decomposed prose goals preserve durable review selections and complete child scope`() {
    val goal = Files.readString(repoRootFromTest().resolve("skills/bill-feature-goal/content.md"))
    val prose = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-prose/content.md"))
    val runner = Files.readString(
      repoRootFromTest().resolve("skills/bill-feature-task-subtask-runner/content.md"),
    )
    val nativeAgents = Files.readString(
      repoRootFromTest().resolve("skills/bill-feature-task-prose/native-agents/agents.yaml"),
    )

    assertContains(goal, "An explicit resumed mode or lane must\nmatch that selection exactly")
    assertContains(goal, "must not overwrite the durable parent or child review policy")
    assertContains(goal, "`baseline_untracked_paths`,\n`completed_review_pass_count`, `reserved_review_pass_number`,")
    assertContains(goal, "current untracked paths - baseline untracked inventory")
    assertContains(prose, "Reject an explicit incompatible mode or lane\nbefore any child work starts")
    assertContains(prose, "An incompatible resume rejection leaves those durable parent and child values\nunchanged")
    assertContains(prose, "current untracked\npaths minus the baseline inventory")
    assertContains(runner, "current untracked paths after subtracting\nthe baseline untracked inventory")
    assertContains(runner, "`baseline_untracked_paths`, `completed_review_pass_count`,")
    assertContains(runner, "the runner must not default, recompute, or replace\nthem")
    assertContains(nativeAgents, "current untracked paths after subtracting `{baseline_untracked_paths}`")
    assertContains(nativeAgents, "Durable review briefing:")
    assertContains(
      nativeAgents,
      "Reject an explicit incompatible mode or lane before child work starts and " +
        "leave durable state unchanged",
    )
    assertContains(runner, "a merge base, or earlier-sibling\nsubtask changes")
  }

  @Test
  fun `decomposed prose review lanes and cap remain coordinated while standalone behavior remains unchanged`() {
    val goal = Files.readString(repoRootFromTest().resolve("skills/bill-feature-goal/content.md"))
    val prose = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-prose/content.md"))
    val runner = Files.readString(
      repoRootFromTest().resolve("skills/bill-feature-task-subtask-runner/content.md"),
    )
    val nativeAgents = Files.readString(
      repoRootFromTest().resolve("skills/bill-feature-task-prose/native-agents/agents.yaml"),
    )

    assertContains(prose, "Do not\npass `parallel:` to either lane")
    assertContains(prose, "together they count as one pass")
    assertContains(runner, "The coordinated lanes are exactly one pass")
    assertContains(nativeAgents, "Invoke both lanes directly and do not pass a parallel argument into either lane")
    assertContains(runner, "resume that accounted pass instead of reserving another")
    assertContains(prose, "never start pass three")
    assertContains(
      prose,
      "Continue\nthrough audit, validation, history, dependency advancement, commit_push, and\n" +
        "final reporting",
    )
    assertContains(runner, "complete\nlocation-bearing evidence in durable artifacts and telemetry")
    assertContains(runner, "class/symbol-or-sanitized label, and concise text")
    assertContains(
      goal,
      "They must never contain a path, line number, diff\nhunk, or raw child-review output",
    )
    assertContains(
      prose,
      "two-pass cap and `review_cap_reached` continuation apply only to\ndecomposed prose-goal children",
    )
    assertContains(
      prose,
      "normal three-iteration repair loop, ordinary `parallel:<agent>`\n" +
        "invocation, approval behavior, and Step 9 PR creation",
    )
  }
}

private fun countOccurrences(haystack: String, needle: String): Int =
  Regex(Regex.escape(needle)).findAll(haystack).count()

private fun repoRootFromTest(): Path {
  var current = Path.of("").toAbsolutePath().normalize()
  while (current.parent != null) {
    val hasSettings = Files.isRegularFile(current.resolve("runtime-kotlin/settings.gradle.kts"))
    val hasContracts = Files.isDirectory(current.resolve("orchestration/contracts"))
    if (hasSettings && hasContracts) {
      return current
    }
    current = current.parent
  }
  error("Could not locate skill-bill repo root from ${Path.of("").toAbsolutePath().normalize()}")
}

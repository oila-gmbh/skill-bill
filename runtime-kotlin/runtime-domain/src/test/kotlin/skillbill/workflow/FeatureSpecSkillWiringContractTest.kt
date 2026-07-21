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
    assertContains(content, "For `no_match`, invoke `bill-feature-spec` first")
    assertContains(content, "Preparation mode is sizing metadata and does not select the executor")
    assertContains(content, "## Direct Dispatch When Governed Artifacts Exist")
    assertContains(content, "A bare governed `spec.md` without `decomposition-manifest.yaml` is intake")
    assertContains(content, "For every authoritative manifest")
    assertContains(content, "Read the file `bill-feature-goal.md` located in this skill's own installed directory")
    assertContains(content, "Do not ask an extra confirmation before dispatching to the goal sidecar")
  }

  @Test
  fun `bill feature continuation routes DB first without a second confirmation or replacement workflow`() {
    val feature = Files.readString(repoRootFromTest().resolve("skills/bill-feature/content.md"))
    val task = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task/content.md"))
    val runtime = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-runtime/content.md"))
    val prose = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-prose/content.md"))

    assertContains(feature, "Before discovering or preparing governed artifacts, perform the read-only")
    assertContains(feature, "The workflow database and immutable execution identity are authoritative")
    assertContains(feature, "Handle `resumable`, `already_running`, `ambiguous`, and `terminal_only`")
    assertContains(feature, "Only `no_match` may continue below")
    assertContains(feature, "workflow-id:<id>")
    assertContains(task, "use continuation mode")
    assertContains(task, "Never open a replacement row or mutate state during lookup")
    assertEquals(1, countOccurrences(task, "Ask exactly one confirmation question"))
    assertContains(runtime, "skill-bill feature-task resume <workflow_id> <issue_key> <spec_path>")
    assertContains(runtime, "deterministically skips\nalready-complete phases")
    assertContains(prose, "feature_task_prose_workflow_continue")
    assertContains(prose, "Do not open a new workflow when continuing an existing run")
  }

  @Test
  fun `feature family forwards one ordered structured agent addon selection without a second gate`() {
    val feature = Files.readString(repoRootFromTest().resolve("skills/bill-feature/content.md"))
    val task = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task/content.md"))
    val goal = Files.readString(repoRootFromTest().resolve("skills/bill-feature-goal/content.md"))
    val prose = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-prose/content.md"))
    val runtime = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-runtime/content.md"))

    assertContains(feature, "Accept zero or more ordered `agent-addon:<slug>` arguments")
    assertContains(feature, "canonical manifest source identity, content digest, and confirmation description")
    assertContains(feature, "No downstream router or worker may parse the original tokens or rediscover")
    assertContains(task, "selected agent add-on slugs and manifest descriptions in caller order, or `none`")
    assertEquals(1, countOccurrences(task, "Ask exactly one confirmation question"))
    assertContains(goal, "Show its slugs and descriptions in\ncaller order in the existing single confirmation")
    assertContains(goal, "forward it unchanged to every runtime or\nprose child and child continuation artifact")
    assertContains(prose, "Before\nevery initial phase, retry, review-fix, audit re-entry, or continuation")
    assertContains(prose, "An empty selection adds no artifact content and no prompt\nsection")
    assertContains(runtime, "Do not parse, reorder, or rediscover it")
    assertFalse(runtime.contains("## Single Confirmation Gate"))
  }

  @Test
  fun `bill feature spec content defines governed intake and modes`() {
    val content = Files.readString(repoRootFromTest().resolve("skills/bill-feature-spec/content.md"))

    assertContains(content, "name: bill-feature-spec")
    assertContains(content, "If the issue key is missing, stop and ask for it.")
    assertContains(content, "one or more distinct executable subtask specs")
    assertContains(content, "Mode is sizing and planning metadata only")
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
    assertContains(content, "`bill-feature-goal` is the trigger surface for manifest-backed goal orchestration")
    assertContains(content, "`skill-bill goal <issue_key>` remains consumer-only")
    assertContains(featureSpecContent, "`skill-bill goal <issue_key>` is consumer-only")
    assertContains(content, "Ask one confirmation question")
    assertEquals(1, countOccurrences(content, "Ask one confirmation question"))
  }

  @Test
  fun `prepared feature guidance uses the manifest as sole source authority`() {
    val runtime = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-runtime/content.md"))
    val prose = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-prose/content.md"))
    val verify = Files.readString(repoRootFromTest().resolve("skills/bill-feature-verify/content.md"))

    assertContains(runtime, "bare `spec.md` is preparation\nintake, not prepared source authority")
    assertContains(prose, "A bare `spec.md` is preparation intake, not prepared source\nauthority")
    assertContains(prose, "exactly one manifest subtask")
    assertContains(verify, "A bare `spec.md` is intake rather than prepared source authority")
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
    assertContains(feature, "When omitted, do not synthesize a `code-review:` token")
    assertContains(feature, "omitting the `code-review:` token when the caller did not provide it")
    assertContains(task, "the requested code-review selection, showing `delegated (default)` when omitted")
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
    assertContains(subtaskRunner, "bill-code-review mode:<code_review_mode>")
    assertFalse(subtaskRunner.contains("bill-code-review mode:code_review_mode"))
    assertContains(goal, "selected mode is immutable for the parent and every child")
    assertContains(review, "`delegated` always runs the normal routed delegated path")
    assertContains(review, "`inline`\nalways runs the complete routed review in the current agent context")
    assertContains(review, "regardless\nof size or risk")
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
  fun `prose review lanes share the two-pass cap while decomposed children retain cap continuation`() {
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
      "Continue past Major, Minor, and Nit findings while preserving them as review evidence",
    )
    assertContains(
      runner,
      "preserve complete location-bearing evidence\nonly in the goal-wide unaddressed-findings ledger",
    )
    assertContains(runner, "class/symbol-or-sanitized label, and concise text")
    assertContains(
      goal,
      "They must never contain a path, line number, diff\nhunk, or raw child-review output",
    )
    assertContains(
      prose,
      "The two-pass cap applies to every feature task",
    )
    assertContains(
      prose,
      "and standalone prose feature tasks stop only when their inline re-review still\n" +
        "has unresolved Blocker findings",
    )
  }

  @Test
  fun `the four governed feature surfaces state one audit-first order and reserve locations for the ledger`() {
    val surfaces = mapOf(
      "skills/bill-feature-goal/content.md" to "Review runs delegated first and inline second.",
      "skills/bill-feature-task-runtime/content.md" to
        "Review runs as a delegated pass followed by an inline pass.",
      "skills/bill-feature-task-prose/content.md" to "Execute review delegated first and inline second.",
      "skills/bill-feature-task-subtask-runner/content.md" to "Review is delegated first, then inline.",
    )

    surfaces.forEach { (path, passSequence) ->
      val content = Files.readString(repoRootFromTest().resolve(path))
      assertContains(content, "implement -> audit -> review -> validate", message = "$path phase order")
      assertContains(content, passSequence, message = "$path pass sequence")
      assertContains(content, "goal-wide unaddressed-findings ledger", message = "$path ledger")
      assertContains(content, "skill-bill goal findings --issue-key <KEY>", message = "$path retrieval surface")
      assertFalse(content.contains("review -> audit"), "$path must not restate a review-before-audit order")
      assertFalse(
        content.contains("durable artifacts and telemetry"),
        "$path must not route location-bearing evidence into telemetry",
      )
    }
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

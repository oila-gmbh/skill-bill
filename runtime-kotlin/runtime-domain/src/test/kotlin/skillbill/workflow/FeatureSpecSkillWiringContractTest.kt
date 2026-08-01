package skillbill.workflow

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewSeverity
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
    assertContains(
      feature,
      "Handle `resumable`, `already_running`, `ambiguous`, `terminal_only`, and `goal_continuation`",
    )
    assertContains(feature, "Only `no_match` may continue below")
    // A goal-orchestrated feature must route to continuation, never to fresh preparation, and must
    // never repair durable state by hand-editing the projection.
    assertContains(feature, "`goal_continuation` means a prepared goal for this issue already owns durable state")
    assertContains(feature, "skill-bill goal accept <issue-key> --subtask <id> --commit <sha> --reason <text>")
    assertContains(feature, "Do not edit `decomposition-manifest.yaml` to force progress.")
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
    assertContains(task, "the requested code-review selection, showing `inline (default)` when omitted")
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
    // Inline is a distinct, shallower depth tier (SKILL-142), no longer "the same review, run inline".
    assertContains(review, "`inline` is the default light tier: one agent in the current context")
    assertContains(review, "equivalent to a delegated result")
    assertContains(review, "Only an explicit `delegated` selection launches workers")
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

  @Test
  fun `goal reopen prose agrees with runtime Blocker-only advancement semantics`() {
    val goalContent = Files.readString(repoRootFromTest().resolve("skills/bill-feature-goal/content.md"))

    // The governed prose must state that only an unresolved Blocker reopens implement_fix
    assertContains(
      goalContent,
      "Only an unresolved Blocker finding reopens `implement_fix`",
      ignoreCase = false,
      message = "goal content must state Blocker-only reopen semantics",
    )

    // The prose must not claim that Major reopens the loop
    assertFalse(
      goalContent.contains("Major finding reopens") ||
        goalContent.contains("Major findings reopen") ||
        goalContent.contains("Blocker or Major finding reopens"),
      "goal content must not claim Major reopens implement_fix",
    )

    // The runtime side: derive the set of severities that require remediation
    val runtimeRemediationSeverities = FeatureTaskRuntimeReviewSeverity.entries
      .filter { it.requiresRemediation }
      .map { it.name }
      .toSet()

    // The prose must claim exactly the same set (only BLOCKER)
    // Since we already asserted "Only an unresolved Blocker finding reopens", this confirms parity
    assertEquals(
      setOf("BLOCKER"),
      runtimeRemediationSeverities,
      "runtime requiresRemediation must be Blocker-only",
    )
  }

  @Test
  fun `goal and runtime guidance forbid in session monitoring and share completion contract`() {
    val goal = Files.readString(repoRootFromTest().resolve("skills/bill-feature-goal/content.md"))
    val runtime = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-runtime/content.md"))
    val surfaces = mapOf("goal" to goal, "runtime" to runtime)

    assertSharedCompletionRules(surfaces)
    assertGoalCompletionRules(goal)
    assertRuntimeCompletionRules(runtime)
  }

  @Test
  fun `feature family uses canonical thin fresh-conversation handoff`() {
    val feature = Files.readString(repoRootFromTest().resolve("skills/bill-feature/content.md"))
    val goal = Files.readString(repoRootFromTest().resolve("skills/bill-feature-goal/content.md"))
    val runtime = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-runtime/content.md"))
    val capabilities = Files.readString(repoRootFromTest().resolve("docs/capabilities.md"))
    listOf(feature, goal, runtime).forEach { content ->
      assertContains(content, "canonical repository realpath")
      assertContains(content, "issue key")
      assertContains(content, "Do not copy")
      assertContains(content, "raw child")
    }
    assertContains(feature, "repository: repo-root-realpath-v1:/absolute/path/to/repository")
    assertContains(goal, "inspect or resume the existing runtime state")
    assertContains(runtime, "sufficient durable-state handoff data")
    assertContains(capabilities, "canonical repository realpath and issue key are sufficient")
  }

  private fun assertSharedCompletionRules(surfaces: Map<String, String>) {
    val sharedRules = listOf(
      "Do not run `skill-bill goal watch` in-session, at any interval or refresh count.",
      "Do not sleep, wait, or otherwise idle in order to re-read progress.",
      "Do not tail, poll, or re-read runtime logs, the workflow DB, `git diff`, or",
      "Do not re-invoke the runtime or launch an observer process or subagent to",
      "The cost rule is request count, not output size:",
      "one completion signal beats any number of short polls",
      "trimming a poll's\noutput does not make polling acceptable.",
      "There is no in-session transition relay; agent silence",
      "For a foreground run within the harness timeout",
      "where the harness provides background-exit notification",
      "When the harness provides no background-exit notification, do not detach.",
      "use the harness's\n   blocking process-completion primitive",
      "Waiting\n   on the original process is a completion signal, not progress polling",
      "loud-fail before launch",
      "Do not read back, summarize, or paraphrase run stdout",
    )

    surfaces.forEach { (name, content) ->
      sharedRules.forEach { rule -> assertContains(content, rule, message = "$name missing shared rule") }
      assertContains(content, "The terminal monitoring block is the user's live feed.")
      assertFalse(
        content.contains("retrieve the\n   original detached command's return once"),
        "$name must not defer terminal delivery until the user speaks",
      )
      assertContains(content, "Do not substitute")
    }
  }

  private fun assertGoalCompletionRules(goal: String) {
    assertContains(goal, "Do not call `skill-bill goal status` on a timer or repeatedly to observe change.")
    assertContains(goal, "The only permitted in-session surface is one bounded terminal notification")
    assertContains(goal, "always emit a terminal notification")
    assertContains(goal, "For\na clean completion, emit exactly two lines")
    assertContains(goal, "goal SKILL-146: finished")
    assertContains(
      goal,
      "summary: Example feature — 3/3 subtasks complete; PR https://github.com/…/pull/241",
    )
    assertContains(goal, "Keep the clean summary to one line")
    assertContains(goal, "Do not reread files or\ninvoke another command to build it.")
    assertContains(goal, "goal SKILL-146: blocked at subtask 2 — <blocked_reason>")
    assertContains(goal, "goal SKILL-146: failed — <blocked_reason>")
    assertContains(goal, "Launch `skill-bill goal` with `--no-live-output`.")
    assertContains(goal, "Goal live output scales with\nwall-clock duration")
    assertContains(goal, "feature-task-runtime `--monitor` is different")
    assertFalse(goal.contains("Keep live output enabled"))
    assertContains(goal, "For the user to follow the goal in their own terminal")
    assertFalse(goal.contains("--max-refreshes"))
    assertContains(goal, "one launch notice, one copyable read-only monitoring")
    assertContains(goal, "Repeated progress, heartbeat, wait,")
    assertFalse(goal.contains("goal_event:"))
    assertContains(goal, "at most 32 files")
    assertContains(goal, "4,096 bytes per excerpt")
    assertContains(goal, "32 KiB total")
    assertContains(goal, "must not emit unrestricted")
    assertContains(goal, "only `{status, commit_sha, workflow_id}`")
  }

  private fun assertRuntimeCompletionRules(runtime: String) {
    assertContains(runtime, "The only permitted in-session surface is exactly one completion line, errors")
    assertContains(runtime, "emit exactly one completion line")
    assertContains(
      runtime,
      "Do not call `skill-bill feature-task status <workflow_id>` on a timer or\n   repeatedly to observe change.",
    )
    assertContains(
      runtime,
      "`status`, `workflow_id`,\n`completed_phases`, `last_incomplete_phase`, and `blocked_reason`",
    )
    assertContains(runtime, "feature-task ft-run-01J8Z0-SKILL-141: complete — 9 phases completed")
    assertContains(runtime, "feature-task ft-run-01J8Z0-SKILL-141: blocked at review — <blocked_reason>")
    assertContains(runtime, "feature-task ft-run-01J8Z0-SKILL-141: failed — <error>")
    assertContains(
      runtime,
      "never block\nsubtask completion solely because install sync is deferred",
    )
    assertContains(runtime, "pass `--monitor` to tee phase transitions to the\nterminal")
    assertContains(runtime, "feature-task-runtime because its output scales\nwith phase count")
    assertContains(runtime, "goal`, whose\nlive output scales with wall-clock duration")
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

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
    assertFalse(content.contains("mode:<mode>"))
  }

  @Test
  fun `bill feature continuation routes DB first without a second confirmation or replacement workflow`() {
    val feature = Files.readString(repoRootFromTest().resolve("skills/bill-feature/content.md"))
    val task = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task/content.md"))
    val runtime = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-runtime/content.md"))

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
    assertContains(task, "delegates to `bill-feature-task-runtime`")
    assertContains(task, "bill-feature-task-runtime.md")
    assertEquals(1, countOccurrences(task, "Ask exactly one confirmation question"))
    assertFalse(task.contains("bill-feature-task-prose"))
    assertFalse(task.contains("mode:prose"))
    assertFalse(task.contains("mode:runtime"))
    assertContains(runtime, "skill-bill feature-task resume <workflow_id> <issue_key> <spec_path>")
    assertContains(runtime, "deterministically skips\nalready-complete phases")
  }

  @Test
  fun `feature family forwards one ordered structured agent addon selection without a second gate`() {
    val feature = Files.readString(repoRootFromTest().resolve("skills/bill-feature/content.md"))
    val task = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task/content.md"))
    val goal = Files.readString(repoRootFromTest().resolve("skills/bill-feature-goal/content.md"))
    val runtime = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-runtime/content.md"))

    assertContains(feature, "Accept zero or more ordered `agent-addon:<slug>` arguments")
    assertContains(feature, "canonical manifest source identity, content digest, and confirmation description")
    assertContains(feature, "No downstream router or worker may parse the original tokens or rediscover")
    assertContains(task, "selected agent add-on slugs and manifest descriptions in caller order, or `none`")
    assertEquals(1, countOccurrences(task, "Ask exactly one confirmation question"))
    assertContains(goal, "Show its slugs and descriptions in\ncaller order in the existing single confirmation")
    assertContains(goal, "forward it unchanged to every runtime\nchild and child continuation artifact")
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
  fun `issue keyed verify workflow openings forward only normalized issue keys`() {
    val verifyContent = Files.readString(repoRootFromTest().resolve("skills/bill-feature-verify/content.md"))

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
    assertContains(content, "hands off to the foreground `skill-bill goal` runtime")
    assertContains(content, "`skill-bill goal <issue_key>` remains consumer-only")
    assertContains(featureSpecContent, "`skill-bill goal <issue_key>` is consumer-only")
    assertContains(content, "Ask one confirmation question")
    assertEquals(1, countOccurrences(content, "Ask one confirmation question"))
    assertFalse(content.contains("mode:prose"))
    assertFalse(content.contains("goal_prose_started"))
    assertFalse(content.contains("bill-feature-task-subtask-runner"))
  }

  @Test
  fun `prepared feature guidance uses the manifest as sole source authority`() {
    val runtime = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-runtime/content.md"))
    val verify = Files.readString(repoRootFromTest().resolve("skills/bill-feature-verify/content.md"))

    assertContains(runtime, "bare `spec.md` is preparation\nintake, not prepared source authority")
    assertContains(verify, "A bare `spec.md` is intake rather than prepared source authority")
  }

  @Test
  fun `review mode source contracts reject invalid selection and preserve the selected mode through runtime entry`() {
    val feature = Files.readString(repoRootFromTest().resolve("skills/bill-feature/content.md"))
    val task = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task/content.md"))
    val goal = Files.readString(repoRootFromTest().resolve("skills/bill-feature-goal/content.md"))
    val review = Files.readString(repoRootFromTest().resolve("skills/bill-code-review/content.md"))
    val runtime = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-runtime/content.md"))

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
    assertContains(goal, "selected mode is immutable for the parent and every child")
    assertContains(review, "`delegated` always runs the normal routed delegated path")
    // Inline is a distinct, shallower depth tier (SKILL-142), no longer "the same review, run inline".
    // It runs in one parent-launched subagent so the parent keeps neither rubric text nor the delta.
    assertContains(review, "`inline` is the single-prompt light tier: one review subagent launched by the")
    assertContains(review, "equivalent to a delegated result")
    assertContains(review, "Omission means `mode:inline`.")
    assertContains(review, "Do not pass `parallel:` into lane 2")
  }

  @Test
  fun `runtime goal child review contract preserves durable review scope`() {
    val goal = Files.readString(repoRootFromTest().resolve("skills/bill-feature-goal/content.md"))

    assertContains(goal, "capture and durably persist that child workflow's `review_base_sha`")
    assertContains(goal, "current untracked paths - baseline untracked inventory")
    assertContains(
      goal,
      "They must never contain a path, line number, diff\nhunk, or raw child-review output",
    )
  }

  @Test
  fun `goal review pass sequence keeps Blocker-or-Major advancement and ledger-only location evidence`() {
    val goal = Files.readString(repoRootFromTest().resolve("skills/bill-feature-goal/content.md"))

    assertContains(
      goal,
      "They must never contain a path, line number, diff\nhunk, or raw child-review output",
    )
    assertContains(goal, "Remediation continues while any unresolved Blocker or Major remains")
  }

  @Test
  fun `the governed feature surfaces state one audit-first order and reserve locations for the ledger`() {
    val passSequence =
      "Review pass one uses the selected mode, and every later pass runs inline against the " +
        "remediation delta via `context:feature-remediation`."
    val surfaces = listOf(
      "skills/bill-feature-goal/content.md",
      "skills/bill-feature-task-runtime/content.md",
    )

    surfaces.forEach { path ->
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
  fun `goal reopen prose agrees with runtime Blocker-or-Major advancement semantics`() {
    val goalContent = Files.readString(repoRootFromTest().resolve("skills/bill-feature-goal/content.md"))

    assertContains(
      goalContent,
      "An unresolved Blocker or Major finding reopens `implement_fix`",
      ignoreCase = false,
      message = "goal content must state Blocker-or-Major reopen semantics",
    )
    assertFalse(
      goalContent.contains("Only an unresolved Blocker finding reopens"),
      "goal content must not retain Blocker-only reopen semantics",
    )
    assertFalse(
      goalContent.contains("a surviving Major moves on"),
      "goal content must not claim a surviving Major advances without remediation",
    )

    val runtimeRemediationSeverities = FeatureTaskRuntimeReviewSeverity.entries
      .filter { it.requiresRemediation }
      .map { it.name }
      .toSet()

    assertEquals(
      setOf("BLOCKER", "MAJOR"),
      runtimeRemediationSeverities,
      "runtime requiresRemediation must be Blocker and Major",
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
    assertContains(goal, "at\nmost 32 eligible files")
    assertContains(goal, "64 headings per file")
    assertContains(goal, "256 headings in total")
    assertContains(goal, "Entry bodies never\ntravel with the catalog")
    assertContains(goal, "selected_boundary_headings")
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

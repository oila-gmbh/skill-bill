package skillbill.cli.goal

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.GoalOperatorDecisionService
import skillbill.application.goalrunner.GoalRunnerStatusService
import skillbill.application.goalrunner.model.GoalRunnerAcceptRequest
import skillbill.application.goalrunner.model.GoalRunnerOperatorDecisionRequest
import skillbill.application.goalrunner.model.GoalRunnerRepairRequest
import skillbill.application.goalrunner.model.GoalRunnerReplanRequest
import skillbill.application.goalrunner.model.GoalRunnerResetRequest
import skillbill.cli.core.CliRunInputs
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import java.nio.file.Path

@Inject
class GoalPauseCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("pause", "Request a durable pause for an already-running goal.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val repoRoot by option("--repo-root", help = "Repository root that owns the goal.")

  override fun run() {
    val result = goalRunnerStatusService.pause(
      issueKey,
      inputs.dbPathOverride,
      repoRoot?.let(Path::of)?.toAbsolutePath()?.normalize() ?: inputs.repositoryRoot,
    )
    val payload = result.toGoalPauseCliMap()
    state.completeText(goalPauseText(payload), payload, exitCode = payload.goalPauseExitCode())
  }
}

@Inject
class GoalStopCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("stop", "Stop a running goal now: record the operator stop, then terminate the runner.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val repoRoot by option("--repo-root", help = "Repository root that owns the goal.")

  override fun run() {
    val result = goalRunnerStatusService.stop(
      issueKey,
      inputs.dbPathOverride,
      repoRoot?.let(Path::of)?.toAbsolutePath()?.normalize() ?: inputs.repositoryRoot,
    )
    val payload = result.toGoalStopCliMap()
    state.completeText(goalStopText(payload), payload, exitCode = payload.goalStopExitCode())
  }
}

@Inject
class GoalResumeCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("resume", "Clear a durable pause for a goal without starting child runs.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val repoRoot by option("--repo-root", help = "Repository root that owns the goal.")

  override fun run() {
    val result = goalRunnerStatusService.resume(
      issueKey,
      inputs.dbPathOverride,
      repoRoot?.let(Path::of)?.toAbsolutePath()?.normalize() ?: inputs.repositoryRoot,
    )
    val payload = result.toGoalResumeCliMap()
    state.completeText(goalResumeText(payload), payload, exitCode = payload.goalPauseExitCode())
  }
}

@Inject
class GoalResetCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("reset", "Reset decomposed goal runtime state.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val hard by option("--hard", help = "Reset all subtask runtime fields, including completed subtasks.")
    .flag(default = false)
  private val force by option("--force", help = "Bypass hard-reset confirmation gate.")
    .flag(default = false)
  private val preservePlanning by option(
    "--preserve-planning",
    help = "Delete incompatible child workflows while preserving immutable goal planning checkpoints.",
  ).flag(default = false)
  private val subtaskId by option(
    "--subtask",
    help = "Selected subtask ID for scoped incompatible-child recovery.",
  ).int()
  private val deleteChildWorkflow by option(
    "--delete-child-workflow",
    help = "Explicitly delete the selected subtask's incompatible terminal child workflow.",
  ).flag(default = false)
  private val confirmIssueKey by option(
    "--confirm-issue-key",
    help = "Confirmation gate for --hard. Must match the issue key.",
  )
  private val repoRoot by option("--repo-root", help = "Repository root for checked-in manifest recovery.")

  override fun run() {
    if ((subtaskId != null) != deleteChildWorkflow) {
      throw UsageError("--subtask ID and --delete-child-workflow must be supplied together.")
    }
    if (subtaskId != null && requireNotNull(subtaskId) <= 0) {
      throw UsageError("--subtask must be a positive integer.")
    }
    if (deleteChildWorkflow && (hard || preservePlanning)) {
      throw UsageError(
        "--subtask ID --delete-child-workflow is incompatible with --hard and --preserve-planning.",
      )
    }
    if (preservePlanning && !hard) {
      throw UsageError(
        "--preserve-planning only applies to a hard reset; a soft reset never deletes child workflows. " +
          "Pass --hard as well, or drop --preserve-planning.",
      )
    }
    if (hard && !force && confirmIssueKey != issueKey) {
      throw UsageError(
        "Hard reset requires explicit confirmation. Pass --confirm-issue-key $issueKey or --force.",
      )
    }
    emitHardResetAcceptanceWarning()
    val result = goalRunnerStatusService.reset(
      GoalRunnerResetRequest(
        issueKey = issueKey,
        hard = hard,
        preservePlanning = preservePlanning,
        subtaskId = subtaskId,
        deleteChildWorkflow = deleteChildWorkflow,
        dbPathOverride = inputs.dbPathOverride,
        repoRoot = repoRoot?.let(Path::of),
      ),
    )
    val payload = result.toGoalResetCliMap(issueKey, hard)
    state.completeText(goalResetText(payload), payload, exitCode = payload.goalResetExitCode())
  }

  private fun emitHardResetAcceptanceWarning() {
    if (!hard) return
    val discardedAcceptances = goalRunnerStatusService.hardResetPreflight(issueKey, inputs.dbPathOverride)
    if (discardedAcceptances.isNotEmpty()) {
      inputs.liveStdout(hardResetAcceptanceWarning(issueKey, discardedAcceptances))
    }
  }
}

@Inject
class GoalReplanCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
  "replan",
  "Discard one subtask plan while preserving sibling plans, shared preplan, and runtime state; " +
    "pass --include-shared-preplan to also discard the shared preplan and cascade non-terminal sibling plans.",
) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val subtaskId by option(
    "--subtask",
    help = "Subtask whose stored plan should be discarded and regenerated on the next goal run.",
  ).int().required()
  private val includeSharedPreplan by option(
    "--include-shared-preplan",
    help = "Also discard the goal-wide shared preplan and cascade sibling plans that are not " +
      "complete with a commit_sha (planning rows only; runtime state and terminal plan rows stay).",
  ).flag(default = false)
  private val repoRoot by option("--repo-root", help = "Repository root for the goal.")

  override fun run() {
    if (subtaskId <= 0) {
      throw UsageError("--subtask must be a positive integer.")
    }
    val result = goalRunnerStatusService.replan(
      GoalRunnerReplanRequest(
        issueKey = issueKey,
        subtaskId = subtaskId,
        dbPathOverride = inputs.dbPathOverride,
        repoRoot = repoRoot?.let(Path::of) ?: inputs.repositoryRoot,
        includeSharedPreplan = includeSharedPreplan,
      ),
    )
    val payload = result.toGoalReplanCliMap(issueKey)
    state.completeText(goalReplanText(payload), payload, exitCode = payload.goalResetExitCode())
  }
}

@Inject
class GoalAcceptCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
  "accept",
  "Restore an acceptance discarded by hard reset (--restore-after-hard-reset only). " +
    "Ordinary out-of-band accept is disabled; repair or resume blocked children instead.",
) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val subtaskId by option("--subtask", help = "Subtask id whose work already landed.")
    .int()
    .required()
  private val commit by option("--commit", help = "Commit that carries the landed work.").required()
  private val reason by option("--reason", help = "Why this subtask was completed outside the runtime.").required()
  private val repoRoot by option("--repo-root", help = "Repository root used to verify the commit.")
  private val restoreAfterHardReset by option(
    "--restore-after-hard-reset",
    help = "Restore an acceptance discarded by a goal-wide hard reset.",
  ).flag(default = false)

  override fun run() {
    val result = goalRunnerStatusService.accept(
      GoalRunnerAcceptRequest(
        issueKey = issueKey,
        subtaskId = subtaskId,
        commitSha = commit,
        reason = reason,
        dbPathOverride = inputs.dbPathOverride,
        repoRoot = repoRoot?.let(Path::of) ?: inputs.repositoryRoot,
        restoreAfterHardReset = restoreAfterHardReset,
      ),
    )
    val payload = result.toGoalAcceptCliMap()
    state.completeText(goalAcceptText(payload), payload, exitCode = payload.goalResetExitCode())
  }
}

@Inject
class GoalRepairCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
  "repair",
  "Inspect or clear known goal-child resume wedges without discarding completed work. " +
    "Default is inspect-only; pass --apply to act. " +
    "Clears: missing validation_depth on the continuation artifact; unreachable stored " +
    "review_base_sha; unreachable stored remediation_base_sha; stale blocked " +
    "goal_continuation_outcome; completed upstream phase records missing settled output for a " +
    "blocked consumer. Does not touch: completed commit shas, review pass history, " +
    "audit repair state, planning checkpoints, or anything goal reset/replan/accept own.",
) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val subtaskId by option(
    "--subtask",
    help = "Optional subtask ID to scope diagnosis and repair to one child.",
  ).int()
  private val apply by option(
    "--apply",
    help = "Apply repairs for diagnosed wedges. Without this flag the command only reports.",
  ).flag(default = false)
  private val repoRoot by option("--repo-root", help = "Repository root for reachability checks.")

  override fun run() {
    if (subtaskId != null && requireNotNull(subtaskId) <= 0) {
      throw UsageError("--subtask must be a positive integer.")
    }
    val result = goalRunnerStatusService.repair(
      GoalRunnerRepairRequest(
        issueKey = issueKey,
        apply = apply,
        subtaskId = subtaskId,
        dbPathOverride = inputs.dbPathOverride,
        repoRoot = repoRoot?.let(Path::of) ?: inputs.repositoryRoot,
      ),
    )
    val payload = result.toGoalRepairCliMap()
    state.completeText(goalRepairText(payload), payload, exitCode = payload.goalRepairExitCode())
  }
}

@Inject
class GoalOperatorDecisionCommand(
  private val goalOperatorDecisionService: GoalOperatorDecisionService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
  "operator-decision",
  "Record retry_fix, accept_and_advance, or abandon_subtask for a paused goal subtask " +
    "without hand-editing durable state or decomposition-manifest.yaml. Resume the goal to consume it.",
) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val subtaskId by option("--subtask", help = "Paused subtask id to decide.")
    .int()
    .required()
  private val decision by option(
    "--decision",
    help = "Operator decision: ${GoalSubtaskOperatorDecision.entries.joinToString { it.wireValue }}.",
  ).required()
  private val repoRoot by option("--repo-root", help = "Repository root for the goal.")

  override fun run() {
    if (subtaskId <= 0) {
      throw UsageError("--subtask must be a positive integer.")
    }
    val parsed = GoalSubtaskOperatorDecision.entries.firstOrNull { it.wireValue == decision }
      ?: throw UsageError(
        "Unknown --decision '$decision'. Allowed: " +
          GoalSubtaskOperatorDecision.entries.joinToString { it.wireValue } + ".",
      )
    val result = goalOperatorDecisionService.record(
      GoalRunnerOperatorDecisionRequest(
        issueKey = issueKey,
        subtaskId = subtaskId,
        decision = parsed,
        dbPathOverride = inputs.dbPathOverride,
        repoRoot = repoRoot?.let(Path::of) ?: inputs.repositoryRoot,
      ),
    )
    val payload = result.toGoalOperatorDecisionCliMap()
    state.completeText(goalOperatorDecisionText(payload), payload, exitCode = payload.goalOperatorDecisionExitCode())
  }
}

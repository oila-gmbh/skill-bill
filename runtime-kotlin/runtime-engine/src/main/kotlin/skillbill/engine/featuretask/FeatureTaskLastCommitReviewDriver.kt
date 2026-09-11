package skillbill.engine.featuretask

import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.ParallelReviewMerger
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewMergeResult

class FeatureTaskLastCommitReviewDriver(
  private val subtaskLauncher: GoalRunnerSubtaskLauncher,
) : FeatureTaskRuntimeReviewDriver {
  override fun run(request: ParallelCodeReviewRequest): ParallelCodeReviewResult {
    val base = requireNotNull(request.baseRevision) { "Last-commit review requires baseRevision." }
    val head = requireNotNull(request.headRevision) { "Last-commit review requires headRevision." }
    val outcome = subtaskLauncher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = request.agent1Id,
        configuredAgentOverrideId = null,
        skillRunRequest = SkillRunRequest(
          issueKey = request.activityWorkflowId?.takeIf(String::isNotBlank) ?: LAST_COMMIT_REVIEW_ISSUE_KEY,
          repoRoot = request.repoRoot,
          timeout = request.timeout,
          promptOverride = request.withSelectedAgentAddons(lastCommitReviewFixPrompt(request, base, head)),
          readOnlyPhase = false,
        ),
      ),
    )
    return when (outcome) {
      is UnsupportedAgentRunLaunch -> failedResult(request.agent1Id, outcome.reason)
      is AgentRunLaunchFacts -> launchedResult(request.agent1Id, outcome)
    }
  }

  private fun launchedResult(agentId: String, facts: AgentRunLaunchFacts): ParallelCodeReviewResult {
    launchFailureReason(facts)?.let { reason -> return failedResult(agentId, reason) }
    val parsed = ParallelReviewFindingParser.parse(facts.stdout)
    val merged = ParallelReviewMerger.merge(
      ParallelReviewLaneResult(agentId = agentId, findings = parsed.findings),
      ParallelReviewLaneResult(agentId = agentId, findings = emptyList()),
    )
    return ParallelCodeReviewResult(
      mergeResult = merged.copy(formattedOutput = facts.stdout.ifBlank { "Review completed." }),
      lane1 = ParallelReviewLaneStatus(
        agentId = agentId,
        success = true,
        droppedCandidateDiagnostic = droppedCandidateDiagnostic(parsed.rejections.size, parsed.candidateCount),
      ),
    )
  }

  private fun failedResult(agentId: String, reason: String) = ParallelCodeReviewResult(
    mergeResult = ParallelReviewMergeResult(findings = emptyList(), formattedOutput = ""),
    lane1 = ParallelReviewLaneStatus(agentId = agentId, success = false, failureReason = reason),
  )

  private fun launchFailureReason(facts: AgentRunLaunchFacts): String? = when {
    facts.timedOut -> "agent timed out"
    facts.spawnFailed -> "agent process failed to spawn"
    facts.interrupted -> "agent was interrupted"
    facts.exitStatus == null -> "agent exited with unknown status"
    facts.exitStatus != 0 -> "agent exited with status ${facts.exitStatus}"
    facts.stdoutTruncated -> "agent output exceeded the retention cap before completion"
    else -> null
  }

  private fun droppedCandidateDiagnostic(rejected: Int, candidateCount: Int): String? =
    if (rejected == 0) {
      null
    } else {
      "dropped $rejected of $candidateCount [F-XXX] candidate line(s)"
    }

  private fun lastCommitReviewFixPrompt(
    request: ParallelCodeReviewRequest,
    baseRevision: String,
    headRevision: String,
  ): String = buildString {
    appendLine("Review the last commit `$headRevision` against its first parent `$baseRevision`.")
    appendLine("Inspect with `git diff $baseRevision $headRevision` in this repository workspace.")
    appendLine("Do not use `origin/main...HEAD`, a merge base, the full feature branch, or a pre-baked diff blob.")
    appendLine("Do not launch bill-code-review, delegated review subagents, or an isolated review process.")
    appendLine("Fix every Blocker and Major finding in this same session before you emit.")
    appendLine("You may edit files. Leave Minor and Nit unfixed unless the edit is local and obvious.")
    appendLine("Criterion-gap detection remains exclusive to audit. Do not report unsatisfied acceptance criteria.")
    appendLine("Do not run `./gradlew check`, the pack collect-all gate, or `bill-code-check`; validate owns those.")
    request.specPath?.let { path ->
      appendLine("Subtask spec path: `$path`.")
    }
    appendLine("After fixes, emit remaining findings in this register shape, one per line:")
    appendLine("- [F-001] Blocker | High | path/File.kt:12 | remaining defect after your edits")
    appendLine("End with exactly one line: `verdict: approved` or `verdict: changes_requested`.")
    appendLine("Use `changes_requested` when any Blocker or Major remains; otherwise `approved`.")
    appendLine("An explicit empty findings list plus `verdict: approved` means no remaining Blocker or Major.")
  }

  private companion object {
    const val LAST_COMMIT_REVIEW_ISSUE_KEY = "code-review"
  }
}

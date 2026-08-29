package skillbill.cli.goal

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskContinuationCandidate
import skillbill.application.goalrunner.GoalPreflightService
import skillbill.application.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.application.goalrunner.model.GoalPreflightRequest
import skillbill.application.goalrunner.model.GoalPreflightResult
import skillbill.application.goalrunner.planning.GoalPlanningLogService
import skillbill.application.goalrunner.planning.model.GoalPlanningLog
import skillbill.application.goalrunner.planning.model.GoalPlanningLogAttempt
import skillbill.application.goalrunner.planning.model.GoalPlanningLogRequest
import skillbill.application.review.RuntimeOwnedReviewMode
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.formatOption
import skillbill.cli.core.invokingAgentResolutionHelp
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.goalrunner.model.UnaddressedFindingsLedger
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedgerEntry
import java.nio.file.Path

@Inject
class GoalPreflightCommand(
  private val service: GoalPreflightService,
  private val state: CliRunState,
) : DocumentedCliCommand(
  "preflight",
  "Show the read-only goal verdict, confirmation gate, and missing spec targets before launch.",
) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val repoRoot by option("--repo-root", help = "Repository root for the goal.")
  private val agent by option(
    "--agent",
    help = invokingAgentResolutionHelp("--agent"),
  )
  private val agentOverride by option(
    "--agent-override",
    help = "Agent to use for child subtask runs instead of the invoking agent.",
  )
  private val codeReviewMode by option(
    "--code-review-mode",
    help = "Review mode: inline (default) or auto.",
  )
  private val agentAddonSlugs by option(
    "--agent-addon",
    help = "Raw agent add-on slug. Repeat to preserve caller order.",
  ).multiple()
  private val format by formatOption()

  override fun run() {
    val root = repoRoot?.let(Path::of)?.toAbsolutePath()?.normalize()
      ?: Path.of("").toAbsolutePath().normalize()
    val invokedAgentId = resolveInvokedAgentId(agent, state.environment)
    val result = service.preflight(
      GoalPreflightRequest(
        issueKey = issueKey,
        repoRoot = root,
        invokedAgentId = invokedAgentId,
        agentOverrideId = agentOverride,
        requestedReviewMode = parseCodeReviewMode(codeReviewMode),
        requestedAgentAddonSlugs = agentAddonSlugs,
        dbPathOverride = state.dbOverride,
        userHome = state.userHome,
        environment = state.environment,
      ),
    )
    val payload = result.toGoalPreflightCliMap()
    state.complete(payload, format)
  }
}

internal fun parseCodeReviewMode(raw: String?): CodeReviewExecutionMode? = raw?.let { value ->
  try {
    RuntimeOwnedReviewMode.parse(value)
  } catch (error: IllegalArgumentException) {
    throw UsageError(error.message ?: "Unknown code-review execution mode.").also { usage ->
      runCatching { usage.initCause(error) }
    }
  }
}

internal fun GoalPreflightResult.toGoalPreflightCliMap(): Map<String, Any?> = linkedMapOf(
  "verdict" to verdict,
  "issue_key" to issueKey,
  "candidate" to candidate?.toGoalPreflightCandidateMap(),
  "candidates" to candidates.map { it.toGoalPreflightCandidateMap() },
  "goal" to goal?.let {
    linkedMapOf(
      "parent_workflow_id" to it.parentWorkflowId,
      "issue_key" to it.issueKey,
      "status" to it.status,
      "current_subtask_id" to it.currentSubtaskId,
      "current_action" to it.currentAction,
      "complete_count" to it.completeCount,
      "pending_count" to it.pendingCount,
      "blocked_count" to it.blockedCount,
      "updated_at" to it.updatedAt,
      "summary" to it.summary,
    )
  },
  "gate_block" to gateBlock?.let { block ->
    linkedMapOf(
      "issue_key" to block.issueKey,
      "feature_name" to block.featureName,
      "subtasks" to block.subtasks.map { subtask ->
        linkedMapOf(
          "id" to subtask.id,
          "name" to subtask.name,
          "status" to subtask.status,
          "dependencies" to subtask.dependencies.map { dependency ->
            linkedMapOf(
              "subtask_id" to dependency.subtaskId,
              "optional" to dependency.optional,
              "skipped" to dependency.skipped,
              "note" to dependency.note,
            )
          },
        )
      },
      "expected_first_runnable_subtask" to block.expectedFirstRunnableSubtask,
      "child_agent" to block.childAgent,
      "child_agent_override" to block.childAgentOverride,
      "review_mode" to block.reviewMode,
      "agent_addons" to block.agentAddons.map { addon ->
        linkedMapOf(
          "slug" to addon.slug,
          "description" to addon.description,
        )
      },
    )
  },
  "rehydrate_targets" to rehydrateTargets.map {
    linkedMapOf(
      "issue_key" to it.issueKey,
      "linear_issue_id" to it.linearIssueId,
      "target_path" to it.targetPath,
    )
  },
  "manifest_missing" to manifestMissing,
)

internal fun FeatureTaskContinuationCandidate.toGoalPreflightCandidateMap(): Map<String, Any?> = linkedMapOf(
  "workflow_id" to workflowId,
  "mode" to mode.wireValue,
  "status" to status,
  "current_step" to currentStep,
  "governed_spec_path" to governedSpecPath,
  "updated_at" to updatedAt,
  "liveness" to liveness?.let {
    linkedMapOf(
      "classification" to it.classification,
      "last_evidence_at" to it.lastEvidenceAt,
      "evidence" to it.evidence,
    )
  },
  "summary" to summary,
)

@Inject
class GoalPlanningLogCommand(
  private val planningLogService: GoalPlanningLogService,
  private val state: CliRunState,
) : DocumentedCliCommand(
  "planning-log",
  "Show the durable goal-planning attempt log: start/end times, durations, outcomes, and failure reasons.",
) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val repoRoot by option("--repo-root", help = "Repository root for checked-in manifest recovery.")
  private val subtask by option(
    "--subtask",
    help = "Show only attempts for this subtask id. Use 0 for the shared preplan.",
  ).int()
  private val failuresOnly by option(
    "--failures-only",
    help = "Show only failed attempts.",
  ).flag(default = false)

  override fun run() {
    val log = planningLogService.log(
      GoalPlanningLogRequest(
        issueKey = issueKey,
        repoRoot = repoRoot?.let(Path::of),
        dbPathOverride = state.dbOverride,
        subtaskId = subtask,
        failuresOnly = failuresOnly,
      ),
    )
    val payload = linkedMapOf<String, Any?>(
      "issue_key" to log.issueKey,
      "parent_workflow_id" to log.parentWorkflowId,
      "total_attempts" to log.totalAttempts,
      "succeeded_attempts" to log.succeededAttempts,
      "failed_attempts" to log.failedAttempts,
      "first_attempt_failures" to log.firstAttemptFailures,
      "phases_observed" to log.phasesObserved,
      "total_planning_ms" to log.totalPlanningMs,
      "attempts" to log.attempts.map { attempt ->
        linkedMapOf<String, Any?>(
          "phase_id" to attempt.phaseId,
          "subtask_id" to attempt.subtaskId,
          "attempt" to attempt.attempt,
          "started_at" to attempt.startedAt?.toString(),
          "finished_at" to attempt.finishedAt?.toString(),
          "duration_ms" to attempt.durationMs,
          "timestamps_inconsistent" to attempt.timestampsInconsistent,
          "outcome" to attempt.outcome,
          "rule" to attempt.rule,
          "reason" to attempt.reason,
          "agent_id" to attempt.agentId,
          "rejected_output_identity" to attempt.rejectedOutputIdentity,
          "rejected_output_bytes" to attempt.rejectedOutputBytes,
        )
      },
    )
    state.completeText(renderPlanningLog(log), payload)
  }
}

/**
 * `none` already means an attempt that has not finished, so an interval the stamps cannot express
 * reads as its own value: an operator sees a record to distrust instead of a plausible blank.
 */
internal fun durationField(attempt: GoalPlanningLogAttempt): String = when {
  attempt.timestampsInconsistent -> "inconsistent"
  else -> attempt.durationMs?.toString() ?: "none"
}

internal fun renderPlanningLog(log: GoalPlanningLog): String = buildString {
  appendLine("issue_key=${log.issueKey} parent_workflow_id=${log.parentWorkflowId ?: "none"}")
  if (log.parentWorkflowId == null) {
    appendLine("no prepared goal found for this issue key in this repository")
    return@buildString
  }
  appendLine(
    "attempts=${log.totalAttempts} succeeded=${log.succeededAttempts} failed=${log.failedAttempts} " +
      "first_attempt_failures=${log.firstAttemptFailures} phases=${log.phasesObserved} " +
      "total_planning_ms=${log.totalPlanningMs}",
  )
  log.attempts.forEach { attempt ->
    appendLine(
      "phase=${attempt.phaseId} attempt=${attempt.attempt} " +
        "started=${attempt.startedAt ?: "unknown"} finished=${attempt.finishedAt ?: "in_flight"} " +
        "duration_ms=${durationField(attempt)} outcome=${attempt.outcome}",
    )
    attempt.reason?.let { reason ->
      appendLine("  rule=${attempt.rule} agent=${attempt.agentId ?: "unknown"} $reason")
    }
    attempt.rejectedOutputIdentity?.let { identity ->
      appendLine(
        "  rejected_output=$identity bytes=${attempt.rejectedOutputBytes ?: 0} " +
          "(read it with: skill-bill feature-task rejected-output " +
          "--workflow ${log.parentWorkflowId} --phase ${attempt.phaseId} " +
          "--attempt ${attempt.attempt} --raw-output)",
      )
    }
  }
}

@Inject
class GoalFindingsCommand(
  private val ledgerService: UnaddressedFindingsLedgerService,
  private val state: CliRunState,
) : DocumentedCliCommand("findings", "Show the goal-wide unaddressed-findings ledger.") {
  private val issueKey by option("--issue-key", help = "Parent issue key.").required()

  override fun run() {
    val ledger = ledgerService.ledger(issueKey, state.dbOverride)
    val repairLedgers = ledgerService.repairLedgersByWorkflow(issueKey, state.dbOverride)
    val verificationDispositions = ledgerService.verificationDispositions(issueKey, state.dbOverride)
    state.completeText(
      findingsText(ledger, repairLedgers, verificationDispositions),
      findingsPayload(ledger, repairLedgers, verificationDispositions),
    )
  }

  private fun findingsPayload(
    ledger: UnaddressedFindingsLedger,
    repairLedgers: Map<String, FeatureTaskRuntimeRepairLedger>,
    verificationDispositions:
    List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): LinkedHashMap<String, Any?> = linkedMapOf(
    "issue_key" to ledger.issueKey,
    "unaddressed_findings" to ledger.findings.size,
    "severity_breakdown" to ledger.severityBreakdown,
    "findings" to ledger.findings.map { finding ->
      linkedMapOf(
        "subtask_id" to finding.subtaskId,
        "workflow_id" to finding.workflowId,
        "review_pass_number" to finding.reviewPassNumber,
        "finding_ordinal" to finding.findingOrdinal,
        "severity" to finding.severity,
        "issue_category" to finding.issueCategory,
        "location" to finding.location,
        "summary" to finding.summary,
        "claim_verdict" to finding.claimVerdict?.wireValue,
        "scope_disposition" to finding.scopeDisposition?.wireValue,
        "citations" to finding.citations.map { citation ->
          linkedMapOf("path" to citation.path, "line" to citation.line)
        },
        "severity_adjustment" to finding.severityAdjustment?.let { adjustment ->
          linkedMapOf(
            "direction" to adjustment.direction.wireValue,
            "justification" to adjustment.justification,
          )
        },
        "verification_disposition" to finding.verificationDisposition,
        "verification_reason" to finding.verificationReason,
      )
    },
    "repair_ledger" to repairLedgers.map { (workflowId, repairLedger) ->
      linkedMapOf<String, Any?>(
        "workflow_id" to workflowId,
        "entries" to repairLedger.entries.map(FeatureTaskRuntimeRepairLedgerEntry::toProjectionMap),
      )
    },
    "finding_verification_dispositions" to verificationDispositions.map { disposition ->
      linkedMapOf(
        "finding_id" to disposition.findingId,
        "disposition" to disposition.disposition.wireValue,
        "reason" to disposition.reason,
        "boundary_context_unavailable" to disposition.boundaryContextUnavailable,
        "selected_boundary_headings" to disposition.selectedBoundaryHeadings.map { heading ->
          linkedMapOf(
            "heading_id" to heading.headingId,
            "source_path" to heading.sourcePath,
          )
        },
      )
    },
  )

  private fun findingsText(
    ledger: UnaddressedFindingsLedger,
    repairLedgers: Map<String, FeatureTaskRuntimeRepairLedger>,
    verificationDispositions:
    List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): String = buildString {
    appendLine("issue_key=${ledger.issueKey} unaddressed_findings=${ledger.findings.size}")
    ledger.findings.forEach { finding ->
      appendLine(
        "subtask=${finding.subtaskId} pass=${finding.reviewPassNumber} " +
          "severity=${finding.severity} category=${finding.issueCategory} " +
          "location=${finding.location} ${finding.summary}" +
          finding.claimVerdict?.let { " claim_verdict=${it.wireValue}" }.orEmpty() +
          finding.scopeDisposition?.let { " scope_disposition=${it.wireValue}" }.orEmpty() +
          finding.verificationDisposition?.let { " verification_disposition=$it" }.orEmpty() +
          finding.verificationReason?.let { " verification_reason=$it" }.orEmpty(),
      )
    }
    repairLedgers.forEach { (workflowId, repairLedger) ->
      repairLedger.entries.forEach { entry ->
        appendLine(
          "repair workflow=$workflowId finding=${entry.disturbanceRef} status=${entry.status.wireValue} " +
            "severity=${entry.severity} round=${entry.originRound} status_round=${entry.statusRound} " +
            "constructs=${entry.constructs.joinToString(",") { it.symbol }} ${entry.intent}",
        )
      }
    }
    verificationDispositions.forEach { disposition ->
      appendLine(
        "verification finding=${disposition.findingId} disposition=${disposition.disposition.wireValue} " +
          "boundary_context_unavailable=${disposition.boundaryContextUnavailable} " +
          "selected_boundary_headings=${
            disposition.selectedBoundaryHeadings.joinToString(";") { "${it.headingId}@${it.sourcePath}" }
          }",
      )
    }
  }
}

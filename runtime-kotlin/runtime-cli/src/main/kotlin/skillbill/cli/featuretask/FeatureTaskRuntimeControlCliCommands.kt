package skillbill.cli.featuretask

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.application.continuation.model.GoalContinuationCandidate
import skillbill.application.featuretask.FeatureTaskContinuationLookupService
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.featuretask.model.FeatureTaskContinuationCandidate
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupResult
import skillbill.application.featuretask.model.FeatureTaskRuntimeStatusRequest
import skillbill.application.workflow.WorkflowService
import skillbill.application.workflow.model.RepairFeatureTaskRuntimeIdentityArgs
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.cli.kernel.CliRunState
import skillbill.cli.kernel.DocumentedCliCommand
import skillbill.cli.kernel.formatOption
import skillbill.cli.kernel.toPayload
import skillbill.cli.model.CliRunInputs
import java.nio.file.Path

@Inject
class FeatureTaskLookupCommand(
  private val lookupService: FeatureTaskContinuationLookupService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
  "lookup",
  "Read-only, repository-scoped lookup of DB-authoritative feature-task continuation state.",
) {
  private val issueKey by argument(help = "Issue key to find.")
  private val repoRoot by option("--repo-root", help = "Path within the Git worktree.").required()
  private val workflowId by option("--workflow-id", help = "Explicit matching workflow selection.")
  private val format by formatOption()

  override fun run() {
    val result =
      lookupService.lookup(issueKey, repositoryIdentity(Path.of(repoRoot)), workflowId, inputs.dbPathOverride)
    val payload = result.toCliPayload()
    state.complete(payload, format, if (result is FeatureTaskContinuationLookupResult.Ambiguous) 2 else 0)
  }
}

private fun FeatureTaskContinuationLookupResult.toCliPayload(): Map<String, Any?> = when (this) {
  FeatureTaskContinuationLookupResult.NoMatch -> mapOf("result" to "no_match")
  is FeatureTaskContinuationLookupResult.Resumable -> mapOf("result" to "resumable", "candidate" to candidate.toMap())
  is FeatureTaskContinuationLookupResult.AlreadyRunning ->
    mapOf("result" to "already_running", "candidate" to candidate.toMap())
  is FeatureTaskContinuationLookupResult.Ambiguous ->
    mapOf("result" to "ambiguous", "candidates" to candidates.map { it.toMap() })
  is FeatureTaskContinuationLookupResult.TerminalOnly ->
    mapOf("result" to "terminal_only", "candidates" to candidates.map { it.toMap() })
  is FeatureTaskContinuationLookupResult.GoalContinuation ->
    mapOf("result" to "goal_continuation", "goal" to candidate.toMap())
  is FeatureTaskContinuationLookupResult.NeedsIdentityRepair -> mapOf(
    "result" to "needs_identity_repair",
    "workflow_id" to workflowId,
    "summary" to summary,
  )
}

private fun GoalContinuationCandidate.toMap(): Map<String, Any?> = mapOf(
  "parent_workflow_id" to parentWorkflowId,
  "issue_key" to issueKey,
  "status" to status,
  "current_subtask_id" to currentSubtaskId,
  "current_action" to currentAction,
  "complete_count" to completeCount,
  "pending_count" to pendingCount,
  "blocked_count" to blockedCount,
  "updated_at" to updatedAt,
  "summary" to summary,
)

private fun FeatureTaskContinuationCandidate.toMap(): Map<String, Any?> = mapOf(
  "workflow_id" to workflowId,
  "mode" to mode.wireValue,
  "status" to status,
  "current_step" to currentStep,
  "governed_spec_path" to governedSpecPath,
  "updated_at" to updatedAt,
  "liveness" to liveness?.let {
    mapOf(
      "classification" to it.classification,
      "last_evidence_at" to it.lastEvidenceAt,
      "evidence" to it.evidence,
    )
  },
  "summary" to summary,
)

@Inject
class FeatureTaskRuntimeStatusCommand(
  private val statusService: FeatureTaskRuntimeStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("status", "Show read-only feature-task phase status.") {
  private val workflowId by argument(help = "Runtime workflow id whose phase status to show.")

  override fun run() {
    val projection = statusService.status(
      FeatureTaskRuntimeStatusRequest(workflowId = workflowId, dbPathOverride = inputs.dbPathOverride),
    )
    val payload = projection.toRuntimeStatusCliMap(workflowId)
    state.completeText(runtimeStatusText(payload), payload, exitCode = payload.runtimeStatusExitCode())
  }
}

@Inject
class FeatureTaskRuntimeResumeCommand(
  private val deps: FeatureTaskRuntimeRunDependencies,
  private val lookupService: FeatureTaskContinuationLookupService,
) : FeatureTaskRuntimePhaseAgentCommand(
  "resume",
  "Resume a feature-task run against an existing workflow id.",
) {
  private val workflowId by argument(help = "Existing runtime workflow id to resume.")
  private val issueKey by argument(help = "Issue key the resumed run implements.")
  private val specPath by argument(help = "Path to the governed spec the run implements.")

  override fun run() {
    validateRuntimeRunConfiguration(deps)
    verifyRuntimeResume(
      VerifyRuntimeResumeArgs(
        lookupService = lookupService,
        inputs = deps.inputs,
        workflowId = workflowId,
        issueKey = issueKey,
        specPath = specPath,
        repoRoot = repoRoot ?: ".",
        goalChild = goalParentIssueKey != null,
      ),
    )
    executeRuntimeRun(
      deps = deps,
      issueKey = requireNotNull(issueKey),
      specPath = specPath,
      workflowId = { workflowId },
    )
  }
}

@Inject
class FeatureTaskRuntimeAbandonCommand(
  private val workflowService: WorkflowService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
  "abandon",
  "Explicitly terminalize a nonterminal feature-task workflow while preserving its durable history.",
) {
  private val workflowId by argument(help = "Exact feature-task workflow id to abandon.")
  private val reason by option("--reason", help = "Required operator reason recorded with the workflow.").required()
  private val format by formatOption()

  override fun run() {
    val result = workflowService.abandonFeatureTaskRuntime(workflowId, reason, inputs.dbPathOverride)
    state.complete(result.toPayload(), format, exitCode = if (result is WorkflowUpdateResult.Error) 1 else 0)
  }
}

@Inject
class FeatureTaskRuntimeRetryBlockedCommand(
  private val workflowService: WorkflowService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
  "retry-blocked",
  "Reopen one blocked runtime phase after an operator-applied fix.",
) {
  private val workflowId by argument(help = "Exact runtime workflow id whose blocked phase should be retried.")
  private val phaseId by option("--phase", help = "Blocked runtime phase to reopen.").required()
  private val reason by option("--reason", help = "Required operator reason recorded with the retry.").required()
  private val format by formatOption()

  override fun run() {
    val result = workflowService.retryBlockedFeatureTaskRuntimePhase(workflowId, phaseId, reason, inputs.dbPathOverride)
    state.complete(result.toPayload(), format, exitCode = if (result is WorkflowUpdateResult.Error) 1 else 0)
  }
}

@Inject
class FeatureTaskRuntimeRepairIdentityCommand(
  private val workflowService: WorkflowService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
  "repair-identity",
  "Explicitly supply missing immutable execution identity for a legacy runtime workflow.",
) {
  private val workflowId by argument(help = "Exact runtime workflow id whose identity is missing.")
  private val issueKey by argument(help = "Issue key persisted by the workflow.")
  private val specPath by argument(help = "Governed spec path for the workflow.")
  private val repoRoot by option("--repo-root", help = "Canonical repository root.").default(".")
  private val reason by option("--reason", help = "Required operator reason recorded with the repair.").required()
  private val format by formatOption()

  override fun run() {
    val root = Path.of(repoRoot)
    val result = workflowService.repairFeatureTaskRuntimeIdentity(
      RepairFeatureTaskRuntimeIdentityArgs(
        workflowId = workflowId,
        issueKey = issueKey,
        repositoryIdentity = repositoryIdentity(root),
        governedSpecPath = governedSpecPath(root, Path.of(specPath)),
        reason = reason,
        dbOverride = inputs.dbPathOverride,
      ),
    )
    state.complete(result.toPayload(), format, exitCode = if (result is WorkflowUpdateResult.Error) 1 else 0)
  }
}

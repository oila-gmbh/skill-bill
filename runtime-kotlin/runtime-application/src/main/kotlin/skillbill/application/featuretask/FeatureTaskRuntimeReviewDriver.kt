package skillbill.application.featuretask

import skillbill.agentaddon.model.AgentAddonPromptFormatter
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelCodeReviewResult
import skillbill.application.model.ParallelReviewLaneStatus
import skillbill.application.model.ParallelReviewScope
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDerivationResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskCommitFocusedAccounting
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration

fun interface FeatureTaskRuntimeReviewDriver {
  fun run(request: ParallelCodeReviewRequest): ParallelCodeReviewResult

  companion object {
    val EMPTY: FeatureTaskRuntimeReviewDriver = FeatureTaskRuntimeReviewDriver { request ->
      ParallelCodeReviewResult(
        mergeResult = ParallelReviewMergeResult(
          findings = emptyList(),
          formattedOutput = "verdict: approved",
        ),
        lane1 = ParallelReviewLaneStatus(agentId = request.agent1Id, success = true),
      )
    }
  }
}

internal data class FeatureTaskRuntimeReviewDriverAgents(
  val agent1Id: String,
)

internal data class FeatureTaskRuntimeReviewDriverPass(
  val passNumber: Int,
  val pinnedMode: CodeReviewExecutionMode,
  val reviewRunId: String,
)

internal data class FeatureTaskRuntimeReviewDriverWorkspace(
  val repoRoot: Path,
  val timeout: Duration?,
  val agentAddonSelection: HydratedAgentAddonSelection,
  val baselineUntrackedPaths: List<String> = emptyList(),
)

internal data class FeatureTaskRuntimeReviewCycleContext(
  val passNumber: Int,
  val resolvedTier: CodeReviewExecutionMode,
  val repositoryFingerprint: String,
  val blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
)

internal object FeatureTaskRuntimeReviewDriverMapper {
  fun request(
    input: GoalSubtaskReviewInput,
    runInvariants: FeatureTaskRuntimeRunInvariants,
    agents: FeatureTaskRuntimeReviewDriverAgents,
    pass: FeatureTaskRuntimeReviewDriverPass,
    workspace: FeatureTaskRuntimeReviewDriverWorkspace,
  ): ParallelCodeReviewRequest {
    val resolution = FeatureTaskRuntimeReviewPassSequence.resolveForPass(pass.pinnedMode, pass.passNumber)
    return ParallelCodeReviewRequest(
      agent1Id = agents.agent1Id,
      scope = ParallelReviewScope.UNSTAGED,
      repoRoot = workspace.repoRoot,
      timeout = workspace.timeout,
      codeReviewMode = resolution.resolvedTier,
      resolvedTier = resolution.resolvedTier,
      suppliedDiff = input.reviewText,
      reviewRunId = pass.reviewRunId,
      baseRevision = input.reviewBaseSha,
      headRevision = input.currentHeadSha,
      specPath = specPath(runInvariants.specReference),
      selectedAgentAddonsSection = AgentAddonPromptFormatter.format(workspace.agentAddonSelection),
      baselineUntrackedPolicy = ParallelCodeReviewRequest.baselineUntrackedPolicy(
        includedPaths = emptyList(),
        excludedPaths = workspace.baselineUntrackedPaths.filter(String::isNotBlank).distinct().sorted(),
      ),
    )
  }

  fun specPath(specReference: String): Path = Path.of(specReference)
}

internal object FeatureTaskRuntimeReviewEnvelope {
  private const val REVIEW_RUN_ID_SUFFIX_LENGTH = 4

  fun assemble(result: ParallelCodeReviewResult): String = result.output.trim().ifBlank { "Review completed." }

  fun extractReviewVerdict(prose: String): FeatureTaskRuntimeVerdict? {
    val context = FeatureTaskRuntimeDerivationContext(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      outputText = prose,
      outputMap = emptyMap(),
    )
    return when (val derived = FeatureTaskRuntimePhaseOutputDerivation.deriveRoutingVerdict(context)) {
      is FeatureTaskRuntimeDerivationResult.Decided -> derived.value
      FeatureTaskRuntimeDerivationResult.Indecisive -> null
    }
  }

  fun envelopeMap(outputText: String): Map<String, Any?> = JsonSupport.parseObjectOrNull(outputText)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    .orEmpty()

  fun mintReviewRunId(): String {
    val stamp = LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    val suffix = CharArray(REVIEW_RUN_ID_SUFFIX_LENGTH) { alphabet.random() }.concatToString()
    return "rvw-$stamp-$suffix"
  }

  internal fun commitFocusedAccounting(
    result: ParallelCodeReviewResult,
    resolvedTier: CodeReviewExecutionMode,
  ): GoalSubtaskCommitFocusedAccounting? {
    val summary = result.accountingSummary ?: return null
    val routing = summary.commitRouting
      ?.takeIf { resolvedTier == CodeReviewExecutionMode.DELEGATED && it.commitCount >= 1 }
      ?: return null
    val accounting = summary.integration
    val pass = result.integration
    val terminalOutcome = accounting?.terminalOutcome
      ?: pass?.terminalOutcome?.wireValue
      ?: GoalSubtaskCommitFocusedAccounting.SKIPPED_NOT_APPLICABLE
    return GoalSubtaskCommitFocusedAccounting(
      commitSequenceDigest = routing.commitSequenceDigest,
      commitCount = routing.commitCount,
      laneCount = routing.laneCount,
      focusedCommitCount = routing.focusedCommitCount,
      skippedCommitCount = routing.skippedCommitCount,
      integrationTerminalOutcome = terminalOutcome,
      routingDigest = routing.routingDigest,
      focusedPairCount = routing.focusedPairCount,
      skippedPairCount = routing.skippedPairCount,
      incompleteLanes = routing.incompleteLanes,
      parentAnalysisPairs = summary.parentAnalysis?.analyzedPairs,
      parentAnalysisBytes = summary.parentAnalysis?.analyzedBytes,
      integrationSkipReason = when (terminalOutcome) {
        GoalSubtaskCommitFocusedAccounting.SKIPPED_NOT_APPLICABLE ->
          accounting?.skipReason?.takeIf { it.isNotBlank() }
            ?: pass?.skipReason?.takeIf { it.isNotBlank() }
            ?: result.coverage?.integrationNotApplicableReason?.takeIf { it.isNotBlank() }
            ?: "commit-focused accounting was recorded without a settled integration pass"
        else -> accounting?.skipReason ?: pass?.skipReason
      },
      integrationFindingCount = accounting?.findingCount ?: pass?.findings?.size,
    )
  }

  internal fun commitFocusedAccounting(
    output: Map<String, Any?>,
    resolvedTier: CodeReviewExecutionMode,
  ): GoalSubtaskCommitFocusedAccounting? {
    if (resolvedTier != CodeReviewExecutionMode.DELEGATED) return null
    val produced = output["produced_outputs"]
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: return null
    val accounting = produced["commit_focused_accounting"]
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: return null
    return GoalSubtaskCommitFocusedAccounting.fromArtifactMap(
      accounting,
      "produced_outputs.commit_focused_accounting",
    )
  }
}

internal data class FeatureTaskRuntimeReviewDriverCycleOutcome(
  val outputText: String,
  val verdict: FeatureTaskRuntimeVerdict,
  val commitFocusedAccounting: GoalSubtaskCommitFocusedAccounting? = null,
)

internal object FeatureTaskRuntimeReviewDriverCycle {
  fun assemble(
    result: ParallelCodeReviewResult,
    request: ParallelCodeReviewRequest,
    cycle: FeatureTaskRuntimeReviewCycleContext,
  ): FeatureTaskRuntimeReviewDriverCycleOutcome {
    val reviewRunId = requireNotNull(request.reviewRunId)
    val accounting = FeatureTaskRuntimeReviewEnvelope.commitFocusedAccounting(result, cycle.resolvedTier)
    val outputText = FeatureTaskRuntimeReviewEnvelope.assemble(result)
    val verdict = FeatureTaskRuntimeReviewEnvelope.extractReviewVerdict(outputText)
      ?: FeatureTaskRuntimeVerdict.APPROVED
    return FeatureTaskRuntimeReviewDriverCycleOutcome(outputText, verdict, accounting)
  }

  fun run(
    driver: FeatureTaskRuntimeReviewDriver,
    request: ParallelCodeReviewRequest,
    cycle: FeatureTaskRuntimeReviewCycleContext,
  ): FeatureTaskRuntimeReviewDriverCycleOutcome = assemble(driver.run(request), request, cycle)
}

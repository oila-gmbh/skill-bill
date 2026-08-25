package skillbill.application.featuretask

import skillbill.agentaddon.model.AgentAddonPromptFormatter
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelCodeReviewResult
import skillbill.application.model.ParallelReviewLaneStatus
import skillbill.application.model.ParallelReviewScope
import skillbill.application.review.toBoundedPayload
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
  ): GoalSubtaskCommitFocusedAccounting? =
    commitFocusedAccountingFromBoundedPayload(result.accountingSummary?.toBoundedPayload(), resolvedTier)
      ?: commitFocusedAccountingFromExecution(result, resolvedTier)

  internal fun commitFocusedAccountingFromBoundedPayload(
    boundedPayload: Map<String, Any?>?,
    resolvedTier: CodeReviewExecutionMode,
  ): GoalSubtaskCommitFocusedAccounting? {
    val payload = boundedPayload?.let(JsonSupport::anyToStringAnyMap) ?: return null
    val routingMap = payload["commit_routing_accounting"]?.let(JsonSupport::anyToStringAnyMap)
    val integrationMap = payload["integration"]?.let(JsonSupport::anyToStringAnyMap)
    val parentAnalysisMap = payload["parent_analysis_consumption"]?.let(JsonSupport::anyToStringAnyMap)
    return commitFocusedAccountingFromMaps(
      routingMap = routingMap,
      parentAnalysisMap = parentAnalysisMap,
      integrationMap = integrationMap,
      resolvedTier = resolvedTier,
    )
  }

  private fun commitFocusedAccountingFromExecution(
    result: ParallelCodeReviewResult,
    resolvedTier: CodeReviewExecutionMode,
  ): GoalSubtaskCommitFocusedAccounting? {
    val routing = result.accountingSummary?.commitRouting
    val parentAnalysis = result.accountingSummary?.parentAnalysis
    val integrationAccounting = result.accountingSummary?.integration
    val integrationPass = result.integration
    val integrationNotApplicableReason = result.coverage?.integrationNotApplicableReason
    if (routing == null || resolvedTier != CodeReviewExecutionMode.DELEGATED || routing.commitCount < 1) {
      return null
    }
    val terminalOutcome = integrationAccounting?.terminalOutcome
      ?: integrationPass?.terminalOutcome?.wireValue
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
      parentAnalysisPairs = parentAnalysis?.analyzedPairs,
      parentAnalysisBytes = parentAnalysis?.analyzedBytes,
      integrationSkipReason = when (terminalOutcome) {
        GoalSubtaskCommitFocusedAccounting.SKIPPED_NOT_APPLICABLE ->
          integrationAccounting?.skipReason?.takeIf { it.isNotBlank() }
            ?: integrationPass?.skipReason?.takeIf { it.isNotBlank() }
            ?: integrationNotApplicableReason?.takeIf { it.isNotBlank() }
            ?: "commit-focused accounting was recorded without a settled integration pass"
        else -> integrationAccounting?.skipReason ?: integrationPass?.skipReason
      },
      integrationFindingCount = integrationAccounting?.findingCount ?: integrationPass?.findings?.size,
    )
  }

  private fun commitFocusedAccountingFromMaps(
    routingMap: Map<String, Any?>?,
    parentAnalysisMap: Map<String, Any?>?,
    integrationMap: Map<String, Any?>?,
    resolvedTier: CodeReviewExecutionMode,
  ): GoalSubtaskCommitFocusedAccounting? {
    if (routingMap == null || resolvedTier != CodeReviewExecutionMode.DELEGATED) return null
    val commitCount = (routingMap["commit_count"] as? Number)?.toInt()?.takeIf { it >= 1 } ?: return null
    val commitSequenceDigest = (routingMap["commit_sequence_digest"] as? String)?.trim().orEmpty()
      .takeIf(String::isNotBlank) ?: return null
    val terminalOutcome = (integrationMap?.get("terminal_outcome") as? String)?.trim()
      ?: GoalSubtaskCommitFocusedAccounting.SKIPPED_NOT_APPLICABLE
    return GoalSubtaskCommitFocusedAccounting(
      commitSequenceDigest = commitSequenceDigest,
      commitCount = commitCount,
      laneCount = (routingMap["lane_count"] as? Number)?.toInt() ?: 0,
      focusedCommitCount = (routingMap["focused_commit_count"] as? Number)?.toInt() ?: 0,
      skippedCommitCount = (routingMap["skipped_commit_count"] as? Number)?.toInt() ?: 0,
      integrationTerminalOutcome = terminalOutcome,
      routingDigest = (routingMap["routing_digest"] as? String)?.trim()?.takeIf(String::isNotBlank),
      focusedPairCount = (routingMap["focused_pair_count"] as? Number)?.toInt(),
      skippedPairCount = (routingMap["skipped_pair_count"] as? Number)?.toInt(),
      incompleteLanes = (routingMap["incomplete_lanes"] as? List<*>)
        ?.mapNotNull { it as? String }
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        .orEmpty(),
      parentAnalysisPairs = (parentAnalysisMap?.get("analyzed_pairs") as? Number)?.toInt(),
      parentAnalysisBytes = (parentAnalysisMap?.get("analyzed_bytes") as? Number)?.toLong(),
      integrationSkipReason = when (terminalOutcome) {
        GoalSubtaskCommitFocusedAccounting.SKIPPED_NOT_APPLICABLE ->
          (integrationMap?.get("skip_reason") as? String)?.trim()?.takeIf(String::isNotBlank)
            ?: "commit-focused accounting was recorded without a settled integration pass"
        else -> (integrationMap?.get("skip_reason") as? String)?.trim()?.takeIf(String::isNotBlank)
      },
      integrationFindingCount = (integrationMap?.get("finding_count") as? Number)?.toInt(),
    )
  }
}

internal data class FeatureTaskRuntimeReviewDriverCycleOutcome(
  val outputText: String,
  val verdict: FeatureTaskRuntimeVerdict?,
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
    return FeatureTaskRuntimeReviewDriverCycleOutcome(outputText, verdict, accounting)
  }

  fun run(
    driver: FeatureTaskRuntimeReviewDriver,
    request: ParallelCodeReviewRequest,
    cycle: FeatureTaskRuntimeReviewCycleContext,
  ): FeatureTaskRuntimeReviewDriverCycleOutcome = assemble(driver.run(request), request, cycle)
}

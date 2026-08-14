package skillbill.application.featuretask

import skillbill.agentaddon.model.AgentAddonPromptFormatter
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.model.DiffResolutionException
import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelCodeReviewResult
import skillbill.application.model.ParallelReviewLaneStatus
import skillbill.application.model.ParallelReviewScope
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingCitation
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskCommitFocusedAccounting
import java.nio.file.Files
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
        mergeResult = ParallelReviewMergeResult(findings = emptyList(), formattedOutput = "NO_FINDINGS"),
        lane1 = ParallelReviewLaneStatus(agentId = request.agent1Id, success = true),
        lane2 = ParallelReviewLaneStatus(agentId = request.agent2Id.orEmpty(), success = true),
      )
    }
  }
}

internal object FeatureTaskRuntimeReviewDriverMapper {
  fun request(
    input: GoalSubtaskReviewInput,
    runInvariants: FeatureTaskRuntimeRunInvariants,
    agent1Id: String,
    parallelReviewAgent: String?,
    passNumber: Int,
    pinnedMode: CodeReviewExecutionMode,
    repoRoot: Path,
    timeout: Duration?,
    reviewRunId: String,
    agentAddonSelection: HydratedAgentAddonSelection,
  ): ParallelCodeReviewRequest {
    val resolution = FeatureTaskRuntimeReviewPassSequence.resolveForPass(pinnedMode, passNumber)
    return ParallelCodeReviewRequest(
      agent1Id = agent1Id,
      agent2Id = parallelReviewAgent?.takeIf(String::isNotBlank),
      scope = ParallelReviewScope.UNSTAGED,
      repoRoot = repoRoot,
      timeout = timeout,
      codeReviewMode = pinnedMode,
      resolvedTier = resolution.resolvedTier,
      suppliedDiff = input.reviewText,
      reviewRunId = reviewRunId,
      baseRevision = input.reviewBaseSha,
      headRevision = input.currentHeadSha,
      specPath = specPath(repoRoot, runInvariants.specReference),
      selectedAgentAddonsSection = AgentAddonPromptFormatter.format(agentAddonSelection),
    )
  }

  fun specPath(repoRoot: Path, specReference: String): Path {
    val declared = Path.of(specReference)
    if (declared.isAbsolute) return declared
    val relative = repoRoot.resolve(specReference)
    return if (Files.isRegularFile(relative)) relative.normalize() else declared
  }
}

internal object FeatureTaskRuntimeReviewEnvelope {
  private val CRITERION_GAP_KEYS = setOf("unmet_criteria", "gaps", "failing_criteria")

  fun assemble(
    result: ParallelCodeReviewResult,
    reviewRunId: String,
    passNumber: Int,
    resolvedTier: CodeReviewExecutionMode,
    repositoryFingerprint: String,
    blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
  ): String {
    val findings = result.mergeResult.findings.map(::findingPayload)
    val produced = linkedMapOf<String, Any?>(
      FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS to findings,
      FeatureTaskRuntimeVerificationSignalKeys.REVIEW_RUN_ID to reviewRunId,
      "repository_checkpoint" to mapOf("fingerprint" to repositoryFingerprint),
    )
    if (passNumber >= 2) {
      produced["blocker_dispositions"] = blockerDispositions.map(GoalSubtaskBlockerDisposition::toArtifactMap)
    }
    commitFocusedAccounting(result, resolvedTier)?.let { accounting ->
      produced["commit_focused_accounting"] = accounting.toArtifactMap()
    }
    CRITERION_GAP_KEYS.forEach { key -> produced.remove(key) }
    val envelope = linkedMapOf<String, Any?>(
      "contract_version" to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
      "phase_id" to "review",
      "status" to STATUS_COMPLETED,
      "summary" to reviewSummary(findings.size),
      "produced_outputs" to produced,
    )
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(envelope)
    envelope[FeatureTaskRuntimeVerificationSignalKeys.VERDICT] = outcome.verdict.wireValue
    return JsonSupport.mapToJsonString(envelope)
  }

  fun envelopeMap(outputText: String): Map<String, Any?> =
    JsonSupport.parseObjectOrNull(outputText)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      .orEmpty()

  fun mintReviewRunId(): String {
    val stamp = LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    val suffix = CharArray(4) { alphabet.random() }.concatToString()
    return "rvw-$stamp-$suffix"
  }

  private fun reviewSummary(findingCount: Int): String =
    if (findingCount == 0) {
      "Runtime-owned review completed with no findings."
    } else {
      "Runtime-owned review completed with $findingCount findings."
    }

  private fun findingPayload(finding: ParallelReviewMergedFinding): Map<String, Any?> = buildMap {
    put("finding_id", finding.fNumber)
    put("severity", finding.severity.name.lowercase())
    put("message", finding.description)
    put("location", finding.location)
    finding.claimVerdict?.let { put("claim_verdict", it.wireValue) }
    finding.scopeDisposition?.let { put("scope_disposition", it.wireValue) }
    if (finding.citations.isNotEmpty()) {
      put("citations", finding.citations.map(::citationPayload))
    }
  }

  private fun citationPayload(citation: ReviewFindingCitation): Map<String, Any?> =
    mapOf("path" to citation.path, "line" to citation.line)

  private fun commitFocusedAccounting(
    result: ParallelCodeReviewResult,
    resolvedTier: CodeReviewExecutionMode,
  ): GoalSubtaskCommitFocusedAccounting? {
    if (resolvedTier != CodeReviewExecutionMode.DELEGATED) return null
    val routing = result.accountingSummary?.commitRouting ?: return null
    if (routing.commitCount < 1) return null
    val integration = result.accountingSummary.integration
    return GoalSubtaskCommitFocusedAccounting(
      commitSequenceDigest = routing.commitSequenceDigest,
      commitCount = routing.commitCount,
      laneCount = routing.laneCount,
      focusedCommitCount = routing.focusedCommitCount,
      skippedCommitCount = routing.skippedCommitCount,
      integrationTerminalOutcome = integration?.terminalOutcome
        ?: GoalSubtaskCommitFocusedAccounting.SKIPPED_NOT_APPLICABLE,
      routingDigest = routing.routingDigest,
      focusedPairCount = routing.focusedPairCount,
      skippedPairCount = routing.skippedPairCount,
      incompleteLanes = routing.incompleteLanes,
      parentAnalysisPairs = result.accountingSummary.parentAnalysis?.analyzedPairs,
      parentAnalysisBytes = result.accountingSummary.parentAnalysis?.analyzedBytes,
      integrationFindingCount = integration?.findingCount,
    )
  }
}

internal data class FeatureTaskRuntimeReviewDriverCycleOutcome(
  val outputText: String,
  val verdict: FeatureTaskRuntimeVerdict,
)

internal object FeatureTaskRuntimeReviewDriverCycle {
  fun assemble(
    result: ParallelCodeReviewResult,
    request: ParallelCodeReviewRequest,
    passNumber: Int,
    resolvedTier: CodeReviewExecutionMode,
    repositoryFingerprint: String,
    blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
  ): FeatureTaskRuntimeReviewDriverCycleOutcome {
    val reviewRunId = requireNotNull(request.reviewRunId)
    val outputText = FeatureTaskRuntimeReviewEnvelope.assemble(
      result = result,
      reviewRunId = reviewRunId,
      passNumber = passNumber,
      resolvedTier = resolvedTier,
      repositoryFingerprint = repositoryFingerprint,
      blockerDispositions = blockerDispositions,
    )
    val envelope = FeatureTaskRuntimeReviewEnvelope.envelopeMap(outputText)
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(envelope)
    return FeatureTaskRuntimeReviewDriverCycleOutcome(outputText, outcome.verdict)
  }

  fun run(
    driver: FeatureTaskRuntimeReviewDriver,
    request: ParallelCodeReviewRequest,
    passNumber: Int,
    resolvedTier: CodeReviewExecutionMode,
    repositoryFingerprint: String,
    blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
  ): FeatureTaskRuntimeReviewDriverCycleOutcome {
    val result = try {
      driver.run(request)
    } catch (error: DiffResolutionException) {
      throw error
    }
    return assemble(
      result = result,
      request = request,
      passNumber = passNumber,
      resolvedTier = resolvedTier,
      repositoryFingerprint = repositoryFingerprint,
      blockerDispositions = blockerDispositions,
    )
  }
}

package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeCrashReconciliationResult
import skillbill.application.featuretask.model.FeatureTaskRuntimeFinishedTelemetryContext
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.application.telemetry.model.FeatureTaskRuntimeFindingVerificationTelemetry
import skillbill.application.telemetry.model.FeatureTaskRuntimeFinishedRequest
import skillbill.application.telemetry.model.FeatureTaskRuntimeRegenerationTelemetry
import skillbill.application.telemetry.normalizedBlockedReason

internal fun emitFeatureTaskRuntimeFinished(
  lifecycleTelemetryService: LifecycleTelemetryService,
  report: FeatureTaskRuntimeRunReport,
  context: FeatureTaskRuntimeFinishedTelemetryContext,
  completionStatus: String,
) {
  val outcomes = context.phaseOutcomes()
  val telemetryPayload = resolvedFeatureTaskRuntimeTelemetryPayload(context)
  lifecycleTelemetryService.featureTaskRuntimeFinished(
    FeatureTaskRuntimeFinishedRequest(
      sessionId = context.telemetrySessionId,
      completionStatus = completionStatus,
      completedPhaseIds = completedPhaseIdsOf(report),
      phaseOutcomes = outcomes,
      lastIncompletePhase = lastIncompletePhaseOf(report, outcomes),
      blockedReason = blockedReasonOf(report),
      resolvedBranch = report.resolvedBranch.orEmpty(),
      reviewFixIterationCount = telemetryPayload.reviewFixIterationCount,
      auditGapIterationCount = telemetryPayload.auditGapIterationCount,
      auditFirstPassConvergence = telemetryPayload.auditFirstPassConvergence,
      auditRecurringGapCount = 0,
      auditNewGapCount = 0,
      auditAttemptedRepairItemCount = 0,
      auditResolvedRepairItemCount = 0,
      regenerationActivationCount = telemetryPayload.regeneration.activationCount,
      regenerationAttemptCount = telemetryPayload.regeneration.attemptCount,
      regenerationOutcomeCounts = telemetryPayload.regeneration.outcomeCounts,
      crashReconciliationCount = telemetryPayload.reconciliation.reconciledCount,
      crashReconciliationReasonCounts = telemetryPayload.reconciliation.reasonClassCounts,
      estimatedPhaseTokenBreakdownJson = telemetryPayload.tokenBreakdownJson,
      estimatedTotalTokens = telemetryPayload.totalTokens,
      findingVerificationVerifiedCount = telemetryPayload.verificationTelemetry.verifiedCount,
      findingVerificationRejectedCount = telemetryPayload.verificationTelemetry.rejectedCount,
      reviewFixCapExhausted = telemetryPayload.verificationTelemetry.reviewFixCapExhausted,
    ),
    dbOverride = context.dbOverride,
  )
}

internal fun emitFeatureTaskRuntimeFinishedError(
  lifecycleTelemetryService: LifecycleTelemetryService,
  context: FeatureTaskRuntimeFinishedTelemetryContext,
  outcomes: Map<String, String>,
) {
  val telemetryPayload = resolvedFeatureTaskRuntimeTelemetryPayload(context)
  lifecycleTelemetryService.featureTaskRuntimeFinished(
    FeatureTaskRuntimeFinishedRequest(
      sessionId = context.telemetrySessionId,
      completionStatus = "error",
      completedPhaseIds = outcomes.filterValues { it == "completed" }.keys.toList(),
      phaseOutcomes = outcomes,
      lastIncompletePhase = outcomes.firstIncompletePhase(),
      blockedReason = normalizedBlockedReason(
        reason = null,
        category = "runtime",
        fallback = "Feature-task-runtime finished with an unhandled error.",
      ),
      resolvedBranch = "",
      reviewFixIterationCount = telemetryPayload.reviewFixIterationCount,
      auditGapIterationCount = telemetryPayload.auditGapIterationCount,
      auditFirstPassConvergence = telemetryPayload.auditFirstPassConvergence,
      auditRecurringGapCount = 0,
      auditNewGapCount = 0,
      auditAttemptedRepairItemCount = 0,
      auditResolvedRepairItemCount = 0,
      regenerationActivationCount = telemetryPayload.regeneration.activationCount,
      regenerationAttemptCount = telemetryPayload.regeneration.attemptCount,
      regenerationOutcomeCounts = telemetryPayload.regeneration.outcomeCounts,
      crashReconciliationCount = telemetryPayload.reconciliation.reconciledCount,
      crashReconciliationReasonCounts = telemetryPayload.reconciliation.reasonClassCounts,
      estimatedPhaseTokenBreakdownJson = telemetryPayload.tokenBreakdownJson,
      estimatedTotalTokens = telemetryPayload.totalTokens,
      findingVerificationVerifiedCount = telemetryPayload.verificationTelemetry.verifiedCount,
      findingVerificationRejectedCount = telemetryPayload.verificationTelemetry.rejectedCount,
      reviewFixCapExhausted = telemetryPayload.verificationTelemetry.reviewFixCapExhausted,
    ),
    dbOverride = context.dbOverride,
  )
}

internal data class ResolvedFeatureTaskRuntimeTelemetryPayload(
  val tokenBreakdownJson: String?,
  val totalTokens: Int?,
  val auditFirstPassConvergence: Boolean,
  val reviewFixIterationCount: Int,
  val auditGapIterationCount: Int,
  val verificationTelemetry: FeatureTaskRuntimeFindingVerificationTelemetry,
  val regeneration: FeatureTaskRuntimeRegenerationTelemetry,
  val reconciliation: FeatureTaskRuntimeCrashReconciliationResult,
)

internal fun resolvedFeatureTaskRuntimeTelemetryPayload(
  context: FeatureTaskRuntimeFinishedTelemetryContext,
): ResolvedFeatureTaskRuntimeTelemetryPayload {
  val (tokenBreakdownJson, totalTokens) = runCatching(context.phaseTokenData).getOrDefault(null to null)
  val auditProgress = runCatching(context.auditRepairProgress).getOrNull()
  val verificationTelemetry = runCatching(context.findingVerificationTelemetry)
    .getOrDefault(FeatureTaskRuntimeFindingVerificationTelemetry())
  val regeneration = runCatching(context.regenerationTelemetry).getOrNull() ?: FeatureTaskRuntimeRegenerationTelemetry()
  val reconciliation = runCatching(context.crashReconciliation).getOrNull()
    ?: FeatureTaskRuntimeCrashReconciliationResult.NONE
  return ResolvedFeatureTaskRuntimeTelemetryPayload(
    tokenBreakdownJson = tokenBreakdownJson,
    totalTokens = totalTokens,
    auditFirstPassConvergence = auditProgress?.firstPassConvergence ?: false,
    reviewFixIterationCount = runCatching(context.reviewFixIterationCount).getOrDefault(0),
    auditGapIterationCount = runCatching(context.auditGapIterationCount).getOrDefault(0),
    verificationTelemetry = verificationTelemetry,
    regeneration = regeneration,
    reconciliation = reconciliation,
  )
}

internal fun completionStatusOf(report: FeatureTaskRuntimeRunReport): String = when (report) {
  is FeatureTaskRuntimeRunReport.Completed -> "completed"
  is FeatureTaskRuntimeRunReport.Blocked -> "blocked"
  is FeatureTaskRuntimeRunReport.Paused -> "paused"
  is FeatureTaskRuntimeRunReport.Decomposed -> "decomposed_at_planning"
}

internal fun completedPhaseIdsOf(report: FeatureTaskRuntimeRunReport): List<String> = when (report) {
  is FeatureTaskRuntimeRunReport.Completed -> report.completedPhaseIds
  is FeatureTaskRuntimeRunReport.Blocked -> report.completedPhaseIds
  is FeatureTaskRuntimeRunReport.Paused -> report.completedPhaseIds
  is FeatureTaskRuntimeRunReport.Decomposed -> report.completedPhaseIds
}

internal fun lastIncompletePhaseOf(report: FeatureTaskRuntimeRunReport, outcomes: Map<String, String>): String =
  when (report) {
    is FeatureTaskRuntimeRunReport.Completed -> "completed"
    is FeatureTaskRuntimeRunReport.Decomposed -> "decomposed_at_planning"
    is FeatureTaskRuntimeRunReport.Paused -> report.pausedPhase
    is FeatureTaskRuntimeRunReport.Blocked ->
      report.lastIncompletePhase.takeIf(String::isNotBlank) ?: outcomes.firstIncompletePhase()
  }

internal fun Map<String, String>.firstIncompletePhase(): String =
  entries.firstOrNull { it.value != "completed" }?.key?.takeIf(String::isNotBlank) ?: "unknown"

internal fun blockedReasonOf(report: FeatureTaskRuntimeRunReport): String = when (report) {
  is FeatureTaskRuntimeRunReport.Blocked -> normalizedBlockedReason(
    reason = report.blockedReason,
    category = "runtime",
    fallback = "Feature-task-runtime blocked without a specific reason.",
  )
  is FeatureTaskRuntimeRunReport.Paused,
  is FeatureTaskRuntimeRunReport.Completed,
  is FeatureTaskRuntimeRunReport.Decomposed,
  -> ""
}

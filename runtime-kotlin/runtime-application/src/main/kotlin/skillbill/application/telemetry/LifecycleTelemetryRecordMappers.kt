package skillbill.application.telemetry

import skillbill.application.model.FeatureImplementFinishedRequest
import skillbill.application.model.FeatureImplementStartedRequest
import skillbill.application.model.FeatureTaskRuntimeFinishedRequest
import skillbill.application.model.FeatureTaskRuntimeStartedRequest
import skillbill.application.model.FeatureVerifyFinishedRequest
import skillbill.application.model.FeatureVerifyStartedRequest
import skillbill.application.model.PrDescriptionGeneratedRequest
import skillbill.application.model.QualityCheckFinishedRequest
import skillbill.application.model.QualityCheckStartedRequest
import skillbill.telemetry.model.FeatureImplementFinishedRecord
import skillbill.telemetry.model.FeatureImplementStartedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import skillbill.telemetry.model.PrDescriptionGeneratedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord

fun FeatureImplementStartedRequest.toRecord(sessionId: String): FeatureImplementStartedRecord =
  FeatureImplementStartedRecord(
    sessionId = sessionId,
    source = source,
    issueKeyProvided = issueKey.isNotBlank(),
    issueKeyType = issueKeyType,
    specInputTypes = specInputTypes,
    specWordCount = specWordCount,
    featureSize = featureSize,
    featureName = featureName,
    rolloutNeeded = rolloutNeeded,
    acceptanceCriteriaCount = acceptanceCriteriaCount,
    openQuestionsCount = openQuestionsCount,
    specSummary = specSummary,
  )

fun FeatureImplementFinishedRequest.toRecord(): FeatureImplementFinishedRecord = FeatureImplementFinishedRecord(
  sessionId = sessionId,
  source = source,
  completionStatus = completionStatus,
  planCorrectionCount = planCorrectionCount,
  planTaskCount = planTaskCount,
  planPhaseCount = planPhaseCount,
  featureFlagUsed = featureFlagUsed,
  featureFlagPattern = featureFlagPattern,
  filesCreated = filesCreated,
  filesModified = filesModified,
  tasksCompleted = tasksCompleted,
  reviewIterations = reviewIterations,
  auditResult = auditResult,
  auditIterations = auditIterations,
  validationResult = validationResult,
  boundaryHistoryWritten = boundaryHistoryWritten,
  boundaryHistoryValue = boundaryHistoryValue,
  prCreated = prCreated,
  planDeviationNotes = planDeviationNotes,
  childSteps = childSteps,
  estimatedPhaseTokenBreakdownJson = estimatedPhaseTokenBreakdownJson,
  estimatedTotalTokens = estimatedTotalTokens,
)

fun FeatureTaskRuntimeStartedRequest.toRecord(sessionId: String): FeatureTaskRuntimeStartedRecord =
  FeatureTaskRuntimeStartedRecord(
    sessionId = sessionId,
    featureSize = featureSize,
    issueKey = issueKey,
    featureName = featureName,
  )

fun FeatureTaskRuntimeFinishedRequest.toRecord(): FeatureTaskRuntimeFinishedRecord = FeatureTaskRuntimeFinishedRecord(
  sessionId = sessionId,
  completionStatus = completionStatus,
  completedPhaseIds = completedPhaseIds,
  phaseOutcomes = phaseOutcomes,
  lastIncompletePhase = lastIncompletePhase,
  blockedReason = blockedReason,
  resolvedBranch = resolvedBranch,
  reviewFixIterationCount = reviewFixIterationCount,
  auditGapIterationCount = auditGapIterationCount,
  auditFirstPassConvergence = auditFirstPassConvergence,
  auditRecurringGapCount = auditRecurringGapCount,
  auditNewGapCount = auditNewGapCount,
  auditAttemptedRepairItemCount = auditAttemptedRepairItemCount,
  auditResolvedRepairItemCount = auditResolvedRepairItemCount,
  regenerationActivationCount = regenerationActivationCount,
  regenerationAttemptCount = regenerationAttemptCount,
  regenerationOutcomeCounts = regenerationOutcomeCounts,
  crashReconciliationCount = crashReconciliationCount,
  crashReconciliationReasonCounts = crashReconciliationReasonCounts,
  estimatedPhaseTokenBreakdownJson = estimatedPhaseTokenBreakdownJson,
  estimatedTotalTokens = estimatedTotalTokens,
)

fun QualityCheckStartedRequest.toRecord(sessionId: String): QualityCheckStartedRecord = QualityCheckStartedRecord(
  sessionId = sessionId,
  routedSkill = routedSkill,
  detectedStack = detectedStack,
  fallback = fallback,
  fallbackReason = fallbackReason,
  scopeType = scopeType,
  initialFailureCount = initialFailureCount,
)

fun QualityCheckFinishedRequest.toRecord(): QualityCheckFinishedRecord = QualityCheckFinishedRecord(
  sessionId = sessionId,
  routedSkill = routedSkill,
  detectedStack = detectedStack,
  fallback = fallback,
  fallbackReason = fallbackReason,
  scopeType = scopeType,
  initialFailureCount = initialFailureCount,
  finalFailureCount = finalFailureCount,
  iterations = iterations,
  result = result,
  failingCheckNames = failingCheckNames,
  unsupportedReason = unsupportedReason,
)

fun FeatureVerifyStartedRequest.toRecord(sessionId: String): FeatureVerifyStartedRecord = FeatureVerifyStartedRecord(
  sessionId = sessionId,
  acceptanceCriteriaCount = acceptanceCriteriaCount,
  rolloutRelevant = rolloutRelevant,
  specSummary = specSummary,
)

fun FeatureVerifyFinishedRequest.toRecord(): FeatureVerifyFinishedRecord = FeatureVerifyFinishedRecord(
  sessionId = sessionId,
  featureFlagAuditPerformed = featureFlagAuditPerformed,
  reviewIterations = reviewIterations,
  auditResult = auditResult,
  completionStatus = completionStatus,
  historyRelevance = historyRelevance,
  historyHelpfulness = historyHelpfulness,
  gapsFound = gapsFound,
)

fun PrDescriptionGeneratedRequest.toRecord(sessionId: String): PrDescriptionGeneratedRecord =
  PrDescriptionGeneratedRecord(
    sessionId = sessionId,
    commitCount = commitCount,
    filesChangedCount = filesChangedCount,
    wasEditedByUser = wasEditedByUser || prDescriptionWasEditedByUser(generatedDescription, finalPrBody),
    prCreated = prCreated,
    prTitle = prTitle,
  )

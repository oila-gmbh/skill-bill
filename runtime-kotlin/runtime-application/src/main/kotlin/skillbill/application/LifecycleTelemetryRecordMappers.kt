package skillbill.application

import skillbill.application.model.FeatureImplementFinishedRequest
import skillbill.application.model.FeatureImplementStartedRequest
import skillbill.application.model.FeatureVerifyFinishedRequest
import skillbill.application.model.FeatureVerifyStartedRequest
import skillbill.application.model.PrDescriptionGeneratedRequest
import skillbill.application.model.QualityCheckFinishedRequest
import skillbill.application.model.QualityCheckStartedRequest
import skillbill.telemetry.model.FeatureImplementFinishedRecord
import skillbill.telemetry.model.FeatureImplementStartedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import skillbill.telemetry.model.PrDescriptionGeneratedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord

fun FeatureImplementStartedRequest.toRecord(sessionId: String): FeatureImplementStartedRecord =
  FeatureImplementStartedRecord(
    sessionId = sessionId,
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
)

fun QualityCheckStartedRequest.toRecord(sessionId: String): QualityCheckStartedRecord = QualityCheckStartedRecord(
  sessionId = sessionId,
  routedSkill = routedSkill,
  detectedStack = detectedStack,
  scopeType = scopeType,
  initialFailureCount = initialFailureCount,
)

fun QualityCheckFinishedRequest.toRecord(): QualityCheckFinishedRecord = QualityCheckFinishedRecord(
  sessionId = sessionId,
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
    wasEditedByUser = wasEditedByUser,
    prCreated = prCreated,
    prTitle = prTitle,
  )

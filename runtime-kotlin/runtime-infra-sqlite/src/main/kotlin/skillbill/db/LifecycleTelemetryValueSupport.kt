package skillbill.db

import skillbill.telemetry.model.FeatureImplementFinishedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord

fun featureImplementFinishedValues(
  record: FeatureImplementFinishedRecord,
  childStepsJson: String,
  includeSessionFirst: Boolean,
): List<Any?> = buildList {
  if (includeSessionFirst) {
    add(record.sessionId)
  }
  add(record.completionStatus)
  add(record.planCorrectionCount)
  add(record.planTaskCount)
  add(record.planPhaseCount)
  add(record.featureFlagUsed.toSqlInt())
  add(record.featureFlagPattern)
  add(record.filesCreated)
  add(record.filesModified)
  add(record.tasksCompleted)
  add(record.reviewIterations)
  add(record.auditResult)
  add(record.auditIterations)
  add(record.validationResult)
  add(record.boundaryHistoryWritten.toSqlInt())
  add(record.boundaryHistoryValue)
  add(record.prCreated.toSqlInt())
  add(record.planDeviationNotes)
  add(childStepsJson)
  if (!includeSessionFirst) {
    add(record.sessionId)
  }
}

fun featureVerifyFinishedValues(
  record: FeatureVerifyFinishedRecord,
  gapsFoundJson: String,
  includeSessionFirst: Boolean,
): List<Any?> = buildList {
  if (includeSessionFirst) {
    add(record.sessionId)
  }
  add(record.featureFlagAuditPerformed.toSqlInt())
  add(record.reviewIterations)
  add(record.auditResult)
  add(record.completionStatus)
  add(record.historyRelevance)
  add(record.historyHelpfulness)
  add(gapsFoundJson)
  if (!includeSessionFirst) {
    add(record.sessionId)
  }
}

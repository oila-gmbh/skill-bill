package skillbill.db.telemetry

import skillbill.telemetry.model.FeatureVerifyFinishedRecord

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

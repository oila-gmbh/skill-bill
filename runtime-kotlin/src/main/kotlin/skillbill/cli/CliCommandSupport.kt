package skillbill.cli

import skillbill.db.DatabaseRuntime
import skillbill.learnings.LearningScope
import skillbill.learnings.learningPayload
import skillbill.learnings.learningSummaryPayload
import skillbill.learnings.scopeCounts
import skillbill.review.LearningRecord
import skillbill.review.NumberedFinding
import skillbill.telemetry.TelemetrySettings
import skillbill.telemetry.telemetrySyncTarget
import java.sql.Connection

internal fun featureStatsPayloadResult(
  dbOverride: String?,
  context: CliRuntimeContext,
  format: CliFormat,
  payloadBuilder: (Connection) -> Map<String, Any?>,
): CliExecutionResult = DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
  payloadResult(
    linkedMapOf<String, Any?>().apply {
      putAll(payloadBuilder(openDb.connection))
      put("db_path", openDb.dbPath.toString())
    },
    format,
  )
}

internal fun findingPayload(finding: NumberedFinding): Map<String, Any?> = linkedMapOf(
  "number" to finding.number,
  "finding_id" to finding.findingId,
  "severity" to finding.severity,
  "confidence" to finding.confidence,
  "location" to finding.location,
  "description" to finding.description,
)

internal fun learningRecordResult(dbPath: String, record: LearningRecord, format: CliFormat): CliExecutionResult =
  payloadResult(
    linkedMapOf<String, Any?>().apply {
      putAll(learningPayload(record))
      put("db_path", dbPath)
    },
    format,
  )

internal fun learningsResolvePayload(
  dbPath: String,
  repoScopeKey: String?,
  skillName: String?,
  reviewSessionId: String?,
  payloadEntries: List<Map<String, Any?>>,
): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>(
  "db_path" to dbPath,
  "repo_scope_key" to repoScopeKey,
  "skill_name" to skillName,
  "scope_precedence" to LearningScope.precedenceWireNames(),
  "applied_learnings" to CliOutput.summarizeAppliedLearnings(payloadEntries),
  "learnings" to payloadEntries,
).also { payload ->
  reviewSessionId?.takeIf(String::isNotBlank)?.let { payload["review_session_id"] = it }
}

internal fun learningsSessionJson(skillName: String?, payloadEntries: List<Map<String, Any?>>): String =
  skillbill.contracts.JsonSupport.mapToJsonString(
    linkedMapOf(
      "skill_name" to skillName,
      "applied_learning_count" to payloadEntries.size,
      "applied_learning_references" to payloadEntries.map { it["reference"] },
      "applied_learnings" to CliOutput.summarizeAppliedLearnings(payloadEntries),
      "scope_counts" to scopeCounts(payloadEntries),
      "learnings" to payloadEntries.map(::learningSummaryPayload),
    ),
  )

internal fun telemetryMutationPayload(settings: TelemetrySettings, clearedEvents: Int): Map<String, Any?> = linkedMapOf(
  "config_path" to settings.configPath.toString(),
  "telemetry_enabled" to settings.enabled,
  "telemetry_level" to settings.level,
  "sync_target" to telemetrySyncTarget(settings),
  "remote_configured" to settings.proxyUrl.isNotBlank(),
  "proxy_configured" to (settings.customProxyUrl != null),
  "proxy_url" to settings.proxyUrl,
  "custom_proxy_url" to settings.customProxyUrl,
  "install_id" to settings.installId,
  "cleared_events" to clearedEvents,
)

internal fun mapWorkflow(workflow: String): String = when (workflow) {
  "verify" -> "bill-feature-verify"
  "implement" -> "bill-feature-implement"
  else -> throw IllegalArgumentException("workflow must be one of: verify, implement.")
}

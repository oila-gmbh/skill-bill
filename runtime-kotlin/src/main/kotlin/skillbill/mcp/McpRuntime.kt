package skillbill.mcp

import skillbill.SkillBillVersion
import skillbill.db.DatabaseRuntime
import skillbill.learnings.LearningScope
import skillbill.learnings.LearningsRuntime
import skillbill.learnings.learningPayload
import skillbill.learnings.learningSessionJson
import skillbill.learnings.summarizeLearningReferences
import skillbill.review.FeedbackRequest
import skillbill.review.FeedbackTelemetryOptions
import skillbill.review.ReviewRuntime
import skillbill.review.ReviewStatsRuntime
import skillbill.review.TriageRuntime
import skillbill.telemetry.HttpRequester
import skillbill.telemetry.RemoteStatsRequest
import skillbill.telemetry.TelemetryConfigRuntime
import skillbill.telemetry.TelemetryHttpRuntime
import skillbill.telemetry.TelemetryRemoteStatsRuntime
import java.nio.file.Files
import java.nio.file.Path

data class McpRuntimeContext(
  val requester: HttpRequester = TelemetryHttpRuntime.defaultHttpRequester,
  val environment: Map<String, String> = System.getenv(),
  val userHome: Path = Path.of(System.getProperty("user.home")),
)

object McpRuntime {
  fun importReview(
    reviewText: String,
    orchestrated: Boolean = false,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    val review = ReviewRuntime.parseReview(reviewText)
    if (!TelemetryConfigRuntime.telemetryIsEnabled(context.environment, context.userHome)) {
      return linkedMapOf(
        "status" to "skipped",
        "reason" to "telemetry is disabled",
        "review_run_id" to review.reviewRunId,
        "finding_count" to review.findings.size,
      )
    }
    val telemetrySettings = loadTelemetrySettings(context)
    DatabaseRuntime
      .openDb(environment = context.environment, userHome = context.userHome)
      .use { openDb ->
        ReviewRuntime.saveImportedReview(openDb.connection, review, sourcePath = null)
        markOrchestrated(openDb.connection, review.reviewRunId, orchestrated)
        val telemetryPayload =
          zeroFindingTelemetryPayload(
            openDb.connection,
            review.reviewRunId,
            review.findings.isEmpty(),
            telemetrySettings,
          )
        return importReviewPayload(openDb.dbPath.toString(), review).also { payload ->
          enrichOrchestratedPayload(payload, telemetryPayload, orchestrated)
        }
      }
  }

  fun triageFindings(
    reviewRunId: String,
    decisions: List<String>,
    orchestrated: Boolean = false,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    if (!TelemetryConfigRuntime.telemetryIsEnabled(context.environment, context.userHome)) {
      return linkedMapOf(
        "status" to "skipped",
        "reason" to "telemetry is disabled",
        "review_run_id" to reviewRunId,
      )
    }
    val telemetrySettings = loadTelemetrySettings(context)
    DatabaseRuntime
      .openDb(environment = context.environment, userHome = context.userHome)
      .use { openDb ->
        markOrchestrated(openDb.connection, reviewRunId, orchestrated)
        val numberedFindings = ReviewRuntime.fetchNumberedFindings(openDb.connection, reviewRunId)
        val parsedDecisions = TriageRuntime.parseTriageDecisions(decisions, numberedFindings)
        val telemetryPayload =
          applyMcpTriageDecisions(
            openDb.connection,
            reviewRunId,
            parsedDecisions,
            FeedbackTelemetryOptions(telemetrySettings.enabled, telemetrySettings.level),
          )
        return triagePayload(openDb.dbPath.toString(), reviewRunId, parsedDecisions).also { payload ->
          enrichOrchestratedPayload(payload, telemetryPayload, orchestrated)
        }
      }
  }

  fun resolveLearnings(
    repo: String? = null,
    skill: String? = null,
    reviewSessionId: String? = null,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    if (!TelemetryConfigRuntime.telemetryIsEnabled(context.environment, context.userHome)) {
      return linkedMapOf(
        "status" to "skipped",
        "reason" to "telemetry is disabled",
        "applied_learnings" to "none",
        "learnings" to emptyList<Map<String, Any?>>(),
      )
    }
    DatabaseRuntime
      .openDb(environment = context.environment, userHome = context.userHome)
      .use { openDb ->
        val (repoScopeKey, skillName, rows) =
          LearningsRuntime.resolveLearnings(openDb.connection, repo, skill)
        val payloadEntries = rows.map(::learningPayload)
        reviewSessionId?.takeIf(String::isNotBlank)?.let {
          LearningsRuntime.saveSessionLearnings(
            openDb.connection,
            it,
            learningSessionJson(skillName, payloadEntries),
          )
        }
        return linkedMapOf<String, Any?>(
          "db_path" to openDb.dbPath.toString(),
          "repo_scope_key" to repoScopeKey,
          "skill_name" to skillName,
          "scope_precedence" to LearningScope.precedenceWireNames(),
          "applied_learnings" to summarizeLearningReferences(payloadEntries),
          "learnings" to payloadEntries,
        ).also { payload ->
          reviewSessionId?.takeIf(String::isNotBlank)?.let { payload["review_session_id"] = it }
        }
      }
  }

  fun reviewStats(reviewRunId: String? = null, context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    featureStatsPayload(context) { connection ->
      ReviewStatsRuntime.statsPayload(connection, reviewRunId)
    }

  fun featureImplementStats(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    featureStatsPayload(context, ReviewStatsRuntime::featureImplementStatsPayload)

  fun featureVerifyStats(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    featureStatsPayload(context, ReviewStatsRuntime::featureVerifyStatsPayload)

  fun telemetryRemoteStats(
    request: RemoteStatsRequest,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = TelemetryRemoteStatsRuntime.fetchRemoteStats(
    request = request,
    settings = loadTelemetrySettings(context),
    requester = context.requester,
    environment = context.environment,
  )

  fun telemetryProxyCapabilities(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    TelemetryHttpRuntime.fetchProxyCapabilities(
      settings = loadTelemetrySettings(context),
      requester = context.requester,
      environment = context.environment,
    )

  fun doctor(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> {
    val dbPath = DatabaseRuntime.resolveDbPath(null, context.environment, context.userHome)
    val settings = runCatching { loadTelemetrySettings(context) }.getOrNull()
    return linkedMapOf(
      "version" to SkillBillVersion.VALUE,
      "db_path" to dbPath.toString(),
      "db_exists" to Files.exists(dbPath),
      "telemetry_enabled" to (settings?.enabled ?: false),
      "telemetry_level" to (settings?.level ?: "off"),
    )
  }
}

private fun importReviewPayload(dbPath: String, review: skillbill.review.ImportedReview): LinkedHashMap<String, Any?> =
  linkedMapOf(
    "db_path" to dbPath,
    "review_run_id" to review.reviewRunId,
    "review_session_id" to review.reviewSessionId,
    "finding_count" to review.findings.size,
    "routed_skill" to review.routedSkill,
    "detected_scope" to review.detectedScope,
    "detected_stack" to review.detectedStack,
    "execution_mode" to review.executionMode,
  )

private fun triagePayload(
  dbPath: String,
  reviewRunId: String,
  parsedDecisions: List<skillbill.review.TriageDecision>,
): LinkedHashMap<String, Any?> = linkedMapOf(
  "db_path" to dbPath,
  "review_run_id" to reviewRunId,
  "recorded" to parsedDecisions.map { decision ->
    linkedMapOf(
      "number" to decision.number,
      "finding_id" to decision.findingId,
      "outcome_type" to decision.outcomeType,
      "note" to decision.note,
    )
  },
)

private fun applyMcpTriageDecisions(
  connection: java.sql.Connection,
  reviewRunId: String,
  parsedDecisions: List<skillbill.review.TriageDecision>,
  telemetryOptions: FeedbackTelemetryOptions,
): Map<String, Any?>? {
  var telemetryPayload: Map<String, Any?>? = null
  parsedDecisions.forEach { decision ->
    val returnedPayload =
      TriageRuntime.recordFeedback(
        connection,
        FeedbackRequest(
          reviewRunId,
          listOf(decision.findingId),
          decision.outcomeType,
          decision.note,
        ),
        telemetryOptions,
      )
    if (returnedPayload != null) {
      telemetryPayload = returnedPayload
    }
  }
  return telemetryPayload
}

private fun featureStatsPayload(
  context: McpRuntimeContext,
  payloadBuilder: (java.sql.Connection) -> Map<String, Any?>,
): Map<String, Any?> = DatabaseRuntime
  .openDb(environment = context.environment, userHome = context.userHome)
  .use { openDb ->
    linkedMapOf<String, Any?>().apply {
      putAll(payloadBuilder(openDb.connection))
      put("db_path", openDb.dbPath.toString())
    }
  }

private fun enrichOrchestratedPayload(
  payload: MutableMap<String, Any?>,
  telemetryPayload: Map<String, Any?>?,
  orchestrated: Boolean,
) {
  if (!orchestrated) {
    return
  }
  payload["mode"] = "orchestrated"
  if (telemetryPayload != null) {
    payload["telemetry_payload"] = linkedMapOf<String, Any?>().apply {
      putAll(telemetryPayload)
      put("skill", "bill-code-review")
    }
  }
}

private fun markOrchestrated(connection: java.sql.Connection, reviewRunId: String, orchestrated: Boolean) {
  if (!orchestrated) {
    return
  }
  connection.prepareStatement(
    "UPDATE review_runs SET orchestrated_run = 1 WHERE review_run_id = ?",
  ).use { statement ->
    statement.setString(1, reviewRunId)
    statement.executeUpdate()
  }
}

private fun zeroFindingTelemetryPayload(
  connection: java.sql.Connection,
  reviewRunId: String,
  hasZeroFindings: Boolean,
  settings: skillbill.telemetry.TelemetrySettings,
): Map<String, Any?>? = if (!hasZeroFindings) {
  null
} else {
  ReviewStatsRuntime.updateReviewFinishedTelemetryState(
    connection,
    reviewRunId,
    enabled = settings.enabled,
    level = settings.level,
  )
}

private fun loadTelemetrySettings(context: McpRuntimeContext): skillbill.telemetry.TelemetrySettings =
  TelemetryConfigRuntime.loadTelemetrySettings(
    environment = context.environment,
    userHome = context.userHome,
  )

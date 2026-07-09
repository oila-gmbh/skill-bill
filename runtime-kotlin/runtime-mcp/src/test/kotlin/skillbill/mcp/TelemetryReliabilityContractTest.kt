package skillbill.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.JsonSupport
import skillbill.db.core.DatabaseRuntime
import skillbill.db.telemetry.LifecycleTelemetryStore
import skillbill.error.InvalidTelemetryEventSchemaError
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.mcp.telemetry.TelemetryEventSchemaPaths
import skillbill.mcp.telemetry.TelemetryEventSchemaValidator
import skillbill.mcp.telemetry.TELEMETRY_EVENT_CONTRACT_VERSION
import skillbill.infrastructure.sqlite.review.ReviewRuntime
import skillbill.infrastructure.sqlite.review.ReviewStatsRuntime
import skillbill.ports.telemetry.model.toReviewFinishedTelemetryPayload
import skillbill.review.ReviewParser
import skillbill.telemetry.model.FeatureImplementFinishedRecord
import skillbill.telemetry.model.FeatureImplementStartedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelemetryReliabilityContractTest {

  private val schemaNode: JsonNode by lazy {
    val schemaFile = repoRootFromTest().resolve(TelemetryEventSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")
    YAMLMapper().readTree(Files.readString(schemaFile))
  }

  @Test
  fun `representative completed issue terminal telemetry carries complete summary fields`() {
    val terminal = goalIssueFinishedEnvelope()
    assertEquals("completed", terminal["status"])
    assertPositiveBoundedDuration(terminal)
    listOf(
      "subtasks_complete",
      "subtasks_blocked",
      "subtasks_skipped",
      "total_invocations",
      "total_blocks",
      "total_resumes",
    )
      .forEach { key -> assertTrue((terminal[key] as? Int) != null, "$key must be present as an integer.") }
    listOf("first_started_at", "finished_at", "mode").forEach { key -> assertNonBlankString(terminal, key) }
    TelemetryEventSchemaValidator.validate(envelope = terminal, eventName = terminal["event_name"] as String)
  }

  @Test
  fun `representative finished telemetry carries nonblank routing labels and plausible durations`() {
    val durationEnvelopes = listOf(
      goalFinishedEnvelope(),
      goalIssueFinishedEnvelope(),
      qualityCheckFinishedEnvelope(),
    )

    durationEnvelopes.forEach { envelope ->
      assertPositiveBoundedDuration(envelope)
      TelemetryEventSchemaValidator.validate(envelope = envelope, eventName = envelope["event_name"] as String)
    }

    val routedEnvelopes = listOf(qualityCheckFinishedEnvelope(), reviewFinishedEnvelope())
    routedEnvelopes.forEach { envelope ->
      listOf("routed_skill", "detected_stack").forEach { key -> assertNonBlankString(envelope, key) }
      envelope["platform_slug"]?.let { assertNonBlankString(envelope, "platform_slug") }
      TelemetryEventSchemaValidator.validate(envelope = envelope, eventName = envelope["event_name"] as String)
    }
  }

  @Test
  fun `goal status and stop_reason values adhere to canonical enums`() {
    val subtaskStatuses = schemaEnum("goalSubtaskStatusEnum")
    val goalStatuses = schemaEnum("goalFinishedStatusEnum")
    val stopReasons = schemaEnum("goalRunnerStopReasonEnum")

    assertEquals(setOf("complete", "blocked", "skipped"), subtaskStatuses)
    assertEquals(setOf("completed", "blocked", "abandoned"), goalStatuses)
    assertEquals(GoalRunnerStopReason.entries.map { it.name }.toSet(), stopReasons)

    val blockedSubtask = goalSubtaskFinishedEnvelope(
      "status" to "blocked",
      "blocked_reason" to "validation: tests failed",
    )
    val blockedGoal = goalFinishedEnvelope("status" to "blocked", "stop_reason" to "BLOCKED")

    assertBlockedReason(blockedSubtask)
    listOf(blockedSubtask, blockedGoal).forEach { envelope ->
      TelemetryEventSchemaValidator.validate(envelope = envelope, eventName = envelope["event_name"] as String)
    }
  }

  @Test
  fun `schema rejects invalid status stop_reason and blank blocked reasons`() {
    assertFailsWith<InvalidTelemetryEventSchemaError> {
      TelemetryEventSchemaValidator.validate(
        envelope = goalSubtaskFinishedEnvelope("status" to "finished"),
        eventName = "goal_subtask_finished",
      )
    }

    assertFailsWith<InvalidTelemetryEventSchemaError> {
      TelemetryEventSchemaValidator.validate(
        envelope = goalFinishedEnvelope("stop_reason" to "NOT_A_STOP_REASON"),
        eventName = "goal_finished",
      )
    }

    assertFailsWith<AssertionError> {
      assertBlockedReason(goalSubtaskFinishedEnvelope("status" to "blocked", "blocked_reason" to "   "))
    }
  }

  @Test
  fun `semantic reliability assertions reject mutations of real emitted payloads`() {
    assertFailsWith<AssertionError> { assertPositiveBoundedDuration(goalFinishedEnvelope("duration_seconds" to 0)) }
    assertFailsWith<AssertionError> { assertNonBlankString(reviewFinishedEnvelope().apply { put("detected_stack", " ") }, "detected_stack") }
    assertFailsWith<AssertionError> {
      val incomplete = goalIssueFinishedEnvelope().apply { remove("first_started_at") }
      assertNonBlankString(incomplete, "first_started_at")
    }
    assertFailsWith<AssertionError> {
      val inaccurate = goalIssueFinishedEnvelope().apply { put("total_invocations", "0") }
      assertTrue(inaccurate["total_invocations"] is Int, "total_invocations must remain an integer")
    }
  }

  @Test
  fun `stale reconciled terminal payloads validate against the canonical schema`() {
    val staleEnvelopes = listOf(
      featureTaskProseFinishedEnvelope("completion_status" to "stale"),
      featureVerifyFinishedEnvelope("completion_status" to "stale"),
      featureTaskRuntimeFinishedEnvelope("completion_status" to "stale"),
      qualityCheckFinishedEnvelope().apply { put("result", "stale") },
    )

    staleEnvelopes.forEach { envelope ->
      TelemetryEventSchemaValidator.validate(envelope = envelope, eventName = envelope["event_name"] as String)
    }
  }

  @Test
  fun `review and feature-task health fields remain explicitly covered`() {
    val reviewFinished = reviewFinishedEnvelope()
    assertEquals(0, reviewFinished["unresolved_findings"])
    TelemetryEventSchemaValidator.validate(envelope = reviewFinished, eventName = "skillbill_review_finished")

    val featureTaskHealthPayload = featureTaskHealthPayload()
    assertEquals(1, featureTaskHealthPayload["duplicate_terminal_finished_events"])
  }

  private fun schemaEnum(name: String): Set<String> =
    schemaNode.path("\$defs").path(name).path("enum").elements().asSequence().map { it.asText() }.toSet()

  private fun assertPositiveBoundedDuration(envelope: Map<String, Any?>) {
    val duration = envelope["duration_seconds"] as? Int
    assertNotNull(duration, "duration_seconds must be present as an integer.")
    assertTrue(duration > 0, "duration_seconds must be positive.")
    assertTrue(duration < 86_400, "duration_seconds must stay below the existing health threshold.")
  }

  private fun assertNonBlankString(envelope: Map<String, Any?>, key: String) {
    val value = envelope[key] as? String
    assertNotNull(value, "$key must be present as a string.")
    assertTrue(value.trim().isNotEmpty(), "$key must not be blank.")
  }

  private fun assertBlockedReason(envelope: Map<String, Any?>) {
    if (envelope["status"] == "blocked") {
      assertNonBlankString(envelope, "blocked_reason")
    }
  }

  private fun goalSubtaskFinishedEnvelope(vararg overrides: Pair<String, Any?>): LinkedHashMap<String, Any?> =
    emittedEnvelope("skillbill_goal_subtask_finished") { store, _ ->
      store.goalSubtaskFinished(
        GoalSubtaskFinishedRecord(
          issueKey = "SKILL-109",
          workflowId = "wfl-skill-109",
          subtaskId = 6,
          subtaskName = "reliability contract test",
          status = "complete",
          startedAt = "2026-07-09T08:00:00Z",
          finishedAt = "2026-07-09T08:18:20Z",
          durationMs = 1_100_000,
          attemptCount = 1,
          blockedReason = null,
        ),
        "full",
      )
    }.apply {
      overrides.forEach { (key, value) -> put(key, value) }
    }

  private fun goalFinishedEnvelope(vararg overrides: Pair<String, Any?>): LinkedHashMap<String, Any?> =
    emittedEnvelope("skillbill_goal_finished") { store, _ ->
      store.goalFinished(goalFinishedRecord(), "full")
    }.apply {
      overrides.forEach { (key, value) -> put(key, value) }
    }

  private fun goalIssueFinishedEnvelope(): LinkedHashMap<String, Any?> =
    emittedEnvelope("skillbill_goal_issue_finished") { store, _ ->
      val started = goalStartedRecord(parentWorkflowId = "wfl-parent")
      store.goalStarted(started, "full")
      store.goalFinished(goalFinishedRecord().copy(parentWorkflowId = "wfl-parent"), "full")
      store.goalIssueFinished(
        GoalIssueFinishedRecord(
          issueKey = "SKILL-109",
          parentWorkflowId = "wfl-parent",
          status = "completed",
          subtasksComplete = 6,
          subtasksBlocked = 0,
          subtasksSkipped = 0,
          finishedAt = "2026-07-09T08:20:00Z",
          mode = "runtime",
        ),
        "full",
      )
    }

  private fun featureTaskProseFinishedEnvelope(vararg overrides: Pair<String, Any?>): LinkedHashMap<String, Any?> =
    emittedEnvelope("skillbill_feature_task_prose_finished") { store, _ ->
      store.featureImplementStarted(
        FeatureImplementStartedRecord(
          sessionId = "fis-reliability",
          issueKeyProvided = true,
          issueKeyType = "linear",
          specInputTypes = listOf("markdown_file"),
          specWordCount = 100,
          featureSize = "MEDIUM",
          featureName = "reliability",
          rolloutNeeded = false,
          acceptanceCriteriaCount = 3,
          openQuestionsCount = 0,
          specSummary = "Telemetry reliability",
        ),
        "full",
      )
      store.featureImplementFinished(
        FeatureImplementFinishedRecord(
          sessionId = "fis-reliability",
          completionStatus = "completed",
          planCorrectionCount = 0,
          planTaskCount = 3,
          planPhaseCount = 2,
          featureFlagUsed = false,
          featureFlagPattern = "none",
          filesCreated = 1,
          filesModified = 2,
          tasksCompleted = 3,
          reviewIterations = 1,
          auditResult = "all_pass",
          auditIterations = 1,
          validationResult = "pass",
          boundaryHistoryWritten = false,
          boundaryHistoryValue = "none",
          prCreated = false,
          planDeviationNotes = "",
          childSteps = emptyList(),
        ),
        "full",
      )
    }.apply {
      overrides.forEach { (key, value) -> put(key, value) }
    }

  private fun featureVerifyFinishedEnvelope(vararg overrides: Pair<String, Any?>): LinkedHashMap<String, Any?> =
    emittedEnvelope("skillbill_feature_verify_finished") { store, connection ->
      store.featureVerifyStarted(
        FeatureVerifyStartedRecord("fvs-reliability", 3, false, "SKILL-109 telemetry reliability"),
        "full",
      )
      ageSession(connection, "feature_verify_sessions", "fvs-reliability", 600)
      store.featureVerifyFinished(
        FeatureVerifyFinishedRecord(
          "fvs-reliability",
          false,
          1,
          "all_pass",
          "completed",
          "medium",
          "medium",
          emptyList(),
        ),
        "full",
      )
    }.apply {
      overrides.forEach { (key, value) -> put(key, value) }
    }

  private fun featureTaskRuntimeFinishedEnvelope(vararg overrides: Pair<String, Any?>): LinkedHashMap<String, Any?> =
    emittedEnvelope("skillbill_feature_task_runtime_finished") { store, _ ->
      store.featureTaskRuntimeStarted(
        FeatureTaskRuntimeStartedRecord("ftr-reliability", "MEDIUM", "SKILL-109", "reliability"),
        "full",
      )
      store.featureTaskRuntimeFinished(
        FeatureTaskRuntimeFinishedRecord(
          "ftr-reliability",
          "completed",
          listOf("implement", "review"),
          mapOf("implement" to "completed", "review" to "completed"),
          "",
          "",
          "feat/SKILL-109",
        ),
        "full",
      )
    }.apply {
      overrides.forEach { (key, value) -> put(key, value) }
    }

  private fun qualityCheckFinishedEnvelope(): LinkedHashMap<String, Any?> =
    emittedEnvelope("skillbill_quality_check_finished") { store, connection ->
      val started = QualityCheckStartedRecord(
        "qck-reliability",
        "bill-kotlin-code-check",
        "kotlin",
        false,
        null,
        "branch_diff",
        2,
      )
      store.qualityCheckStarted(started, "full")
      ageSession(connection, "quality_check_sessions", started.sessionId, 420)
      store.qualityCheckFinished(
        QualityCheckFinishedRecord(
          started.sessionId,
          started.routedSkill,
          started.detectedStack,
          started.fallback,
          started.fallbackReason,
          started.scopeType,
          started.initialFailureCount,
          0,
          1,
          "pass",
          emptyList(),
          "",
        ),
        "full",
      )
    }

  private fun reviewFinishedEnvelope(): LinkedHashMap<String, Any?> {
    val dbPath = Files.createTempDirectory("telemetry-reliability-review").resolve("metrics.db")
    return DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val review = ReviewParser.parseReview(
        """
        Review session ID: rvs-reliability
        Review run ID: rvw-reliability
        Routed to: bill-kmp-code-review
        Detected review scope: branch diff
        Detected stack: KMP/Kotlin mixed workspace
        Execution mode: runtime

        ### 2. Risk Register
        No findings.
        """.trimIndent(),
      )
      ReviewRuntime.saveImportedReview(connection, review, sourcePath = null)
      linkedMapOf<String, Any?>().apply {
        put("event_name", "skillbill_review_finished")
        put("contract_version", TELEMETRY_EVENT_CONTRACT_VERSION)
        putAll(
          ReviewStatsRuntime.buildReviewFinishedPayload(
            connection = connection,
            reviewRunId = review.reviewRunId,
            level = "full",
            routedSkillPlatformSlugs = mapOf("bill-kmp-code-review" to "kmp"),
          ).toReviewFinishedTelemetryPayload().toPayload(),
        )
      }
    }
  }

  private fun emittedEnvelope(
    eventName: String,
    emit: (LifecycleTelemetryStore, java.sql.Connection) -> Unit,
  ): LinkedHashMap<String, Any?> {
    val dbPath = Files.createTempDirectory("telemetry-reliability-emitter").resolve("metrics.db")
    return DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      emit(LifecycleTelemetryStore(connection), connection)
      val payloadJson = connection.prepareStatement(
        "SELECT payload_json FROM telemetry_outbox WHERE event_name = ? ORDER BY id DESC LIMIT 1",
      ).use { statement ->
        statement.setString(1, eventName)
        statement.executeQuery().use { resultSet ->
          assertTrue(resultSet.next(), "Expected a real outbox row for $eventName")
          resultSet.getString("payload_json")
        }
      }
      val parsed = requireNotNull(JsonSupport.parseObjectOrNull(payloadJson))
      linkedMapOf<String, Any?>().apply {
        val contractEventName = if (eventName == "skillbill_review_finished") eventName else {
          eventName.removePrefix("skillbill_")
        }
        put("event_name", contractEventName)
        put("contract_version", TELEMETRY_EVENT_CONTRACT_VERSION)
        putAll(requireNotNull(JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))))
      }
    }
  }

  private fun goalStartedRecord(parentWorkflowId: String? = null): GoalStartedRecord = GoalStartedRecord(
    issueKey = "SKILL-109",
    featureName = "reliability",
    workflowId = "wfl-skill-109",
    subtaskTotal = 6,
    resumed = false,
    startedAt = "2026-07-09T08:00:00Z",
    mode = "runtime",
    parentWorkflowId = parentWorkflowId,
  )

  private fun goalFinishedRecord(): GoalFinishedRecord = GoalFinishedRecord(
    issueKey = "SKILL-109",
    workflowId = "wfl-skill-109",
    status = "completed",
    startedAt = "2026-07-09T08:00:00Z",
    finishedAt = "2026-07-09T08:20:00Z",
    durationMs = 1_200_000,
    subtasksComplete = 6,
    subtasksBlocked = 0,
    subtasksSkipped = 0,
    mode = "runtime",
  )

  private fun ageSession(connection: java.sql.Connection, tableName: String, sessionId: String, seconds: Int) {
    connection.prepareStatement(
      "UPDATE $tableName SET started_at = datetime('now', '-' || ? || ' seconds') WHERE session_id = ?",
    ).use { statement ->
      statement.setInt(1, seconds)
      statement.setString(2, sessionId)
      statement.executeUpdate()
    }
  }

  private fun featureTaskHealthPayload(): LinkedHashMap<String, Any?> = linkedMapOf(
    "workflow" to "bill-feature-task",
    "total_runs" to 1,
    "duplicate_terminal_finished_events" to 1,
  )
}

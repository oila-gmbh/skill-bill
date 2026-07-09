package skillbill.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.error.InvalidTelemetryEventSchemaError
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.mcp.telemetry.TELEMETRY_EVENT_CONTRACT_VERSION
import skillbill.mcp.telemetry.TelemetryEventSchemaPaths
import skillbill.mcp.telemetry.TelemetryEventSchemaValidator
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
  fun `completed issue-level goal sequences carry exactly one complete issue terminal event`() {
    val sequence = listOf(
      RecordedTelemetryEvent("skillbill_goal_started", goalStartedEnvelope()),
      RecordedTelemetryEvent("skillbill_goal_subtask_finished", goalSubtaskFinishedEnvelope()),
      RecordedTelemetryEvent("skillbill_goal_finished", goalFinishedEnvelope()),
      RecordedTelemetryEvent("skillbill_goal_issue_finished", goalIssueFinishedEnvelope()),
    )

    val issueTerminalEvents = sequence.filter { it.persistedEventName == "skillbill_goal_issue_finished" }
    assertEquals(1, issueTerminalEvents.size, "Completed issue sequences must emit exactly one issue terminal event.")

    val terminal = issueTerminalEvents.single().envelope
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

    sequence.forEach { recorded ->
      TelemetryEventSchemaValidator.validate(
        envelope = recorded.envelope,
        eventName = recorded.envelope["event_name"] as String,
      )
    }
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

  private fun base(eventName: String): LinkedHashMap<String, Any?> =
    linkedMapOf("event_name" to eventName, "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION)

  private data class RecordedTelemetryEvent(
    val persistedEventName: String,
    val envelope: LinkedHashMap<String, Any?>,
  )

  private fun goalStartedEnvelope(): LinkedHashMap<String, Any?> = base("goal_started").apply {
    put("issue_key", "SKILL-109")
    put("feature_name", "reliable-telemetry")
    put("workflow_id", "wfl-skill-109")
    put("subtask_total", 6)
    put("resumed", false)
    put("started_at", "2026-07-09T08:00:00Z")
    put("status", "running")
    put("mode", "runtime")
  }

  private fun goalSubtaskFinishedEnvelope(vararg overrides: Pair<String, Any?>): LinkedHashMap<String, Any?> =
    base("goal_subtask_finished").apply {
      put("issue_key", "SKILL-109")
      put("workflow_id", "wfl-skill-109")
      put("subtask_id", 6)
      put("subtask_name", "reliability contract test")
      put("status", "complete")
      put("started_at", "2026-07-09T08:00:00Z")
      put("finished_at", "2026-07-09T08:18:20Z")
      put("duration_seconds", 1_100)
      put("attempt_count", 1)
      put("blocked_reason", null)
      overrides.forEach { (key, value) -> put(key, value) }
    }

  private fun goalFinishedEnvelope(vararg overrides: Pair<String, Any?>): LinkedHashMap<String, Any?> =
    base("goal_finished").apply {
      put("issue_key", "SKILL-109")
      put("workflow_id", "wfl-skill-109")
      put("status", "completed")
      put("started_at", "2026-07-09T08:00:00Z")
      put("finished_at", "2026-07-09T08:20:00Z")
      put("duration_seconds", 1_200)
      put("subtasks_complete", 6)
      put("subtasks_blocked", 0)
      put("subtasks_skipped", 0)
      put("mode", "runtime")
      put("stop_reason", null)
      overrides.forEach { (key, value) -> put(key, value) }
    }

  private fun goalIssueFinishedEnvelope(): LinkedHashMap<String, Any?> = base("goal_issue_finished").apply {
    put("issue_key", "SKILL-109")
    put("parent_workflow_id", "wfl-skill-109")
    put("status", "completed")
    put("subtasks_complete", 6)
    put("subtasks_blocked", 0)
    put("subtasks_skipped", 0)
    put("total_invocations", 7)
    put("total_blocks", 1)
    put("total_resumes", 1)
    put("first_started_at", "2026-07-09T08:00:00Z")
    put("finished_at", "2026-07-09T08:20:00Z")
    put("duration_seconds", 1_200)
    put("mode", "runtime")
  }

  private fun qualityCheckFinishedEnvelope(): LinkedHashMap<String, Any?> = base("quality_check_finished").apply {
    put("session_id", "qck-20260709-080000-a1b2")
    put("routed_skill", "bill-kotlin-code-check")
    put("detected_stack", "kotlin")
    put("fallback", false)
    put("scope_type", "branch_diff")
    put("initial_failure_count", 2)
    put("final_failure_count", 0)
    put("iterations", 1)
    put("result", "pass")
    put("duration_seconds", 420)
    put("failing_check_names", emptyList<String>())
    put("unsupported_reason", "")
    put("orchestrated", false)
  }

  private fun reviewFinishedEnvelope(): LinkedHashMap<String, Any?> = base("skillbill_review_finished").apply {
    put("total_findings", 1)
    put("accepted_findings", 1)
    put("rejected_findings", 0)
    put("unresolved_findings", 0)
    put("accepted_rate", 1.0)
    put("rejected_rate", 0.0)
    put("accepted_finding_details", listOf(mapOf("severity" to "Major", "message" to "Accepted.")))
    put("rejected_finding_details", emptyList<Map<String, Any?>>())
    put("review_run_id", "rvw-20260709-080000-a1b2")
    put("review_session_id", "rvs-20260709-080000-a1b2")
    put("routed_skill", "bill-kotlin-code-review")
    put("review_subskills", listOf("testing"))
    put("review_scope", "branch_diff")
    put("review_platform", "kotlin")
    put("detected_stack", "kotlin")
    put("fallback", false)
    put("platform_slug", "kotlin")
    put("scope_type", "branch_diff")
    put("execution_mode", "runtime")
    put("review_finished_at", "2026-07-09T08:20:00Z")
    put("learnings", mapOf("captured" to true))
  }

  private fun featureTaskHealthPayload(): LinkedHashMap<String, Any?> = linkedMapOf(
    "workflow" to "bill-feature-task",
    "total_runs" to 1,
    "duplicate_terminal_finished_events" to 1,
  )
}

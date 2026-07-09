package skillbill.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.mcp.core.McpToolRegistry
import skillbill.mcp.telemetry.TELEMETRY_EVENT_CONTRACT_VERSION
import skillbill.mcp.telemetry.TelemetryEventSchemaPaths
import skillbill.mcp.telemetry.TelemetryEventSchemaValidator
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SKILL-66 Subtask 1 (AC4): per-family parity test for the
 * runtime-internal emission events
 * (`goal_started`, `goal_subtask_finished`, `goal_finished`, `goal_issue_finished`,
 * `skillbill_review_finished`).
 *
 * Modeled on `TelemetryTaskRuntimeStepIdEnumParityTest`: the canonical
 * schema carries `$defs/<name>Event` branches for these payloads
 * so the runtime's goal-telemetry emissions are schema-validated, but
 * the events are intentionally NOT MCP tools (no `McpToolRegistry`
 * entry, no dispatcher handler, no input schema). This test pins three
 * invariants:
 *
 *  1. Each emission branch exists with `additionalProperties: false`,
 *     the right `event_name`/`contract_version` consts, and the exact
 *     required keyset the runtime persistence/emission code (Subtasks
 *     2/3) will rely on.
 *  2. A representative envelope per event validates clean against the
 *     canonical schema — proving the branches are reachable through the
 *     top-level `oneOf` and that `blocked_reason`'s `["string","null"]`
 *     union validates for BOTH a string and `null`.
 *  3. None of these names appear in `McpToolRegistry.tools` (the
 *     `x-coherence-checks.goal-telemetry-emission-events` invariant in
 *     test form).
 */
class GoalTelemetryEmissionEventParityTest {

  private val schemaNode: JsonNode by lazy {
    val schemaFile = repoRootFromTest().resolve(TelemetryEventSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")
    YAMLMapper().readTree(Files.readString(schemaFile))
  }

  @Test
  fun `emission branches exist with strict shape and expected required keys`() {
    assertGoalSegmentBranches()
    assertGoalTerminalBranches()
    assertReviewFinishedBranch()
  }

  private fun assertGoalSegmentBranches() {
    assertBranch(
      branchName = "goalStartedEvent",
      eventName = "goal_started",
      expectedRequired = setOf(
        "event_name",
        "contract_version",
        "issue_key",
        "feature_name",
        "workflow_id",
        "subtask_total",
        "resumed",
        "started_at",
        "status",
        "mode",
      ),
    )
    assertBranch(
      branchName = "goalSubtaskFinishedEvent",
      eventName = "goal_subtask_finished",
      expectedRequired = setOf(
        "event_name",
        "contract_version",
        "issue_key",
        "workflow_id",
        "subtask_id",
        "subtask_name",
        "status",
        "started_at",
        "finished_at",
        "duration_seconds",
        "attempt_count",
        "blocked_reason",
      ),
    )
  }

  private fun assertGoalTerminalBranches() {
    assertBranch(
      branchName = "goalFinishedEvent",
      eventName = "goal_finished",
      expectedRequired = setOf(
        "event_name",
        "contract_version",
        "issue_key",
        "workflow_id",
        "status",
        "started_at",
        "finished_at",
        "duration_seconds",
        "subtasks_complete",
        "subtasks_blocked",
        "subtasks_skipped",
        "mode",
        "stop_reason",
      ),
    )
    assertBranch(
      branchName = "goalIssueFinishedEvent",
      eventName = "goal_issue_finished",
      expectedRequired = setOf(
        "event_name",
        "contract_version",
        "issue_key",
        "parent_workflow_id",
        "status",
        "subtasks_complete",
        "subtasks_blocked",
        "subtasks_skipped",
        "total_invocations",
        "total_blocks",
        "total_resumes",
        "first_started_at",
        "finished_at",
        "duration_seconds",
        "mode",
      ),
    )
  }

  private fun assertReviewFinishedBranch() {
    assertBranch(
      branchName = "skillbillReviewFinishedEvent",
      eventName = "skillbill_review_finished",
      expectedRequired = setOf(
        "event_name",
        "contract_version",
        "total_findings",
        "accepted_findings",
        "rejected_findings",
        "unresolved_findings",
        "accepted_rate",
        "rejected_rate",
        "accepted_finding_details",
        "rejected_finding_details",
        "review_run_id",
        "review_session_id",
        "routed_skill",
        "review_subskills",
        "review_scope",
        "review_platform",
        "detected_stack",
        "fallback",
        "platform_slug",
        "scope_type",
        "execution_mode",
        "review_finished_at",
        "learnings",
      ),
    )
  }

  @Test
  fun `goal_started and goal_finished representative envelopes validate clean`() {
    TelemetryEventSchemaValidator.validate(
      envelope = linkedMapOf(
        "event_name" to "goal_started",
        "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
        "issue_key" to "SKILL-66",
        "feature_name" to "feature-goal-telemetry",
        "workflow_id" to "wfl-goal-1",
        "subtask_total" to 4,
        "resumed" to false,
        "started_at" to "2026-06-04T10:15:30Z",
        "status" to "running",
        "mode" to "runtime",
      ),
      eventName = "goal_started",
    )

    TelemetryEventSchemaValidator.validate(
      envelope = linkedMapOf(
        "event_name" to "goal_finished",
        "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
        "issue_key" to "SKILL-66",
        "workflow_id" to "wfl-goal-1",
        "status" to "blocked",
        "started_at" to "2026-06-04T10:15:30Z",
        "finished_at" to "2026-06-04T12:01:44Z",
        "duration_seconds" to 6_374,
        "subtasks_complete" to 3,
        "subtasks_blocked" to 1,
        "subtasks_skipped" to 0,
        "mode" to "prose",
        "stop_reason" to "BLOCKED",
      ),
      eventName = "goal_finished",
    )

    TelemetryEventSchemaValidator.validate(
      envelope = linkedMapOf(
        "event_name" to "goal_issue_finished",
        "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
        "issue_key" to "SKILL-109",
        "parent_workflow_id" to "wfl-parent",
        "status" to "completed",
        "subtasks_complete" to 3,
        "subtasks_blocked" to 0,
        "subtasks_skipped" to 0,
        "total_invocations" to 3,
        "total_blocks" to 2,
        "total_resumes" to 2,
        "first_started_at" to "2026-06-04T10:15:30Z",
        "finished_at" to "2026-06-04T12:01:44Z",
        "duration_seconds" to 6_374,
        "mode" to "runtime",
      ),
      eventName = "goal_issue_finished",
    )
  }

  @Test
  fun `goal_subtask_finished envelopes validate clean including blocked_reason and history fields`() {
    val subtaskFinishedBase = linkedMapOf<String, Any?>(
      "event_name" to "goal_subtask_finished",
      "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
      "issue_key" to "SKILL-66",
      "workflow_id" to "wfl-goal-1",
      "subtask_id" to 1,
      "subtask_name" to "goal-telemetry-contract-and-schema",
      "status" to "complete",
      "started_at" to "2026-06-04T10:15:30Z",
      "finished_at" to "2026-06-04T10:42:05Z",
      "duration_seconds" to 1_595,
      "attempt_count" to 1,
    )

    TelemetryEventSchemaValidator.validate(
      envelope = LinkedHashMap(subtaskFinishedBase).apply { put("blocked_reason", null) },
      eventName = "goal_subtask_finished",
    )

    TelemetryEventSchemaValidator.validate(
      envelope = LinkedHashMap(subtaskFinishedBase).apply {
        put("status", "blocked")
        put("blocked_reason", "validation: gate failed twice")
      },
      eventName = "goal_subtask_finished",
    )

    TelemetryEventSchemaValidator.validate(
      envelope = LinkedHashMap(subtaskFinishedBase).apply {
        put("blocked_reason", null)
        put("boundary_history_value", "high")
        put("boundary_history_written", true)
      },
      eventName = "goal_subtask_finished",
    )
  }

  @Test
  fun `review_finished representative envelope validates clean with normalized stack fields`() {
    TelemetryEventSchemaValidator.validate(
      envelope = linkedMapOf(
        "event_name" to "skillbill_review_finished",
        "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
        "total_findings" to 1,
        "accepted_findings" to 1,
        "rejected_findings" to 0,
        "unresolved_findings" to 0,
        "accepted_rate" to 1.0,
        "rejected_rate" to 0.0,
        "accepted_finding_details" to listOf(
          linkedMapOf(
            "severity" to "Major",
            "message" to "Finding accepted by reviewer.",
          ),
        ),
        "rejected_finding_details" to emptyList<Map<String, Any?>>(),
        "review_run_id" to "rvw-109",
        "review_session_id" to "rvs-109",
        "routed_skill" to "bill-kmp-code-check",
        "review_subskills" to listOf("testing"),
        "review_scope" to "branch_diff",
        "review_platform" to "kmp",
        "detected_stack" to "kmp",
        "detected_stack_detail" to "kmp→kotlin fallback",
        "fallback" to true,
        "fallback_reason" to "kotlin_quality_check_fallback",
        "platform_slug" to "kmp",
        "scope_type" to "branch_diff",
        "execution_mode" to "runtime",
        "review_finished_at" to "2026-06-04T12:01:44Z",
        "learnings" to linkedMapOf("captured" to true),
      ),
      eventName = "skillbill_review_finished",
    )
  }

  @Test
  fun `goal runner stop reason schema enum matches runtime enum`() {
    val schemaValues = schemaNode.path("\$defs")
      .path("goalRunnerStopReasonEnum")
      .path("enum")
      .elements()
      .asSequence()
      .map { it.asText() }
      .toSet()

    assertEquals(
      GoalRunnerStopReason.entries.map { it.name }.toSet(),
      schemaValues,
      "goalRunnerStopReasonEnum must accept every runtime GoalRunnerStopReason.",
    )
  }

  @Test
  fun `emission events are not registered MCP tools`() {
    val toolNames = McpToolRegistry.tools.map { it.name }.toSet()
    listOf(
      "goal_started",
      "goal_subtask_finished",
      "goal_finished",
      "goal_issue_finished",
      "skillbill_review_finished",
    ).forEach { name ->
      assertFalse(
        name in toolNames,
        "Emission event '$name' must NOT be a registered MCP tool (runtime-internal only). " +
          "See x-coherence-checks.goal-telemetry-emission-events.",
      )
    }
    // The companion stats tool IS registered.
    assertTrue("goal_stats" in toolNames, "goal_stats must be a registered MCP tool.")
  }

  private fun assertBranch(branchName: String, eventName: String, expectedRequired: Set<String>) {
    val branch = schemaNode.path("\$defs").path(branchName)
    assertTrue(!branch.isMissingNode, "Schema is missing branch '\$defs/$branchName'.")

    assertEquals(
      false,
      branch.path("additionalProperties").asBoolean(true),
      "Branch '$branchName' must be strict (additionalProperties: false).",
    )
    assertEquals(
      eventName,
      branch.path("properties").path("event_name").path("const").asText(""),
      "Branch '$branchName' must pin event_name.const to '$eventName'.",
    )
    assertEquals(
      TELEMETRY_EVENT_CONTRACT_VERSION,
      branch.path("properties").path("contract_version").path("const").asText(""),
      "Branch '$branchName' must pin contract_version.const to TELEMETRY_EVENT_CONTRACT_VERSION.",
    )

    val actualRequired = branch.path("required").elements().asSequence().map { it.asText() }.toSet()
    assertEquals(
      expectedRequired,
      actualRequired,
      "Branch '$branchName' required[] does not match the expected emission keyset.",
    )
  }
}

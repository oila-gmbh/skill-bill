package skillbill.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SKILL-66 Subtask 1 (AC4): per-family parity test for the
 * runtime-internal decomposed-goal emission events
 * (`goal_started`, `goal_subtask_finished`, `goal_finished`).
 *
 * Modeled on `TelemetryTaskRuntimeStepIdEnumParityTest`: the canonical
 * schema carries `$defs/<name>Event` branches for these three payloads
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
 *  3. None of the three names appear in `McpToolRegistry.tools` (the
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
        "duration_ms",
        "attempt_count",
        "blocked_reason",
      ),
    )
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
        "duration_ms",
        "subtasks_complete",
        "subtasks_blocked",
        "subtasks_skipped",
      ),
    )
  }

  @Test
  fun `representative emission envelopes validate clean including blocked_reason null and string`() {
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
      ),
      eventName = "goal_started",
    )

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
      "duration_ms" to 1_595_000,
      "attempt_count" to 1,
    )

    // blocked_reason as null (the `complete` case).
    TelemetryEventSchemaValidator.validate(
      envelope = LinkedHashMap(subtaskFinishedBase).apply { put("blocked_reason", null) },
      eventName = "goal_subtask_finished",
    )

    // blocked_reason as a string (the `blocked` case).
    TelemetryEventSchemaValidator.validate(
      envelope = LinkedHashMap(subtaskFinishedBase).apply {
        put("status", "blocked")
        put("blocked_reason", "validation gate failed twice")
      },
      eventName = "goal_subtask_finished",
    )

    TelemetryEventSchemaValidator.validate(
      envelope = linkedMapOf(
        "event_name" to "goal_finished",
        "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
        "issue_key" to "SKILL-66",
        "workflow_id" to "wfl-goal-1",
        "status" to "completed",
        "started_at" to "2026-06-04T10:15:30Z",
        "finished_at" to "2026-06-04T12:01:44Z",
        "duration_ms" to 6_374_000,
        "subtasks_complete" to 3,
        "subtasks_blocked" to 1,
        "subtasks_skipped" to 0,
      ),
      eventName = "goal_finished",
    )
  }

  @Test
  fun `emission events are not registered MCP tools`() {
    val toolNames = McpToolRegistry.tools.map { it.name }.toSet()
    listOf("goal_started", "goal_subtask_finished", "goal_finished").forEach { name ->
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

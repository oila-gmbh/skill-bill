package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.contracts.JsonSupport
import skillbill.infrastructure.fs.GitWorkflowGitOperations
import skillbill.ports.workflow.repositoryFingerprint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliWorkflowUpdateRuntimeTest {
  @Test
  fun `workflow update returns compact acknowledgement and show remains full state`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-workflow-update")
    val dbPath = tempDir.resolve("metrics.db")
    val opened = runJson("--db", dbPath.toString(), "workflow", "open", "--format", "json")
    val workflowId = opened["workflow_id"] as String

    val update = runJson(
      "--db",
      dbPath.toString(),
      "workflow",
      "update",
      workflowId,
      "--workflow-status",
      "blocked",
      "--current-step-id",
      "implement",
      "--step-updates",
      """[{"step_id":"implement","status":"blocked","attempt_count":1}]""",
      "--artifacts-patch",
      """{"preplan_digest":{"ok":true}}""",
      "--format",
      "json",
    )
    assertCompactUpdate(
      payload = update,
      stepId = "implement",
      artifactKeys = listOf("preplan_digest"),
      readOnlyCommand = "skill-bill --db '$dbPath' workflow show '$workflowId' --format json",
    )

    val shown = runJson("--db", dbPath.toString(), "workflow", "show", workflowId, "--format", "json")
    val artifacts = shown["artifacts"] as Map<*, *>
    assertEquals(mapOf("ok" to true), artifacts["preplan_digest"])
    assertTrue(shown.containsKey("steps"))
  }

  @Test
  fun `verify workflow update returns compact acknowledgement`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-verify-workflow-update")
    val dbPath = tempDir.resolve("metrics.db")
    val opened = runJson(
      "--db",
      dbPath.toString(),
      "verify-workflow",
      "open",
      "--current-step-id",
      "code_review",
      "--format",
      "json",
    )
    val workflowId = opened["workflow_id"] as String
    val checkpoint = GitWorkflowGitOperations().repositoryFingerprint(Path.of("").toAbsolutePath()).value

    val update = runJson(
      "--db",
      dbPath.toString(),
      "verify-workflow",
      "update",
      workflowId,
      "--workflow-status",
      "running",
      "--current-step-id",
      "verdict",
      "--step-updates",
      """[{"step_id":"verdict","status":"blocked","attempt_count":1}]""",
      "--artifacts-patch",
      "{" +
        "\"diff_projection\":{\"checkpoint\":\"$checkpoint\"," +
        "\"comparison_scope\":\"base..head\",\"changed_files\":[]}," +
        "\"feature_flag_audit_receipt\":{\"contract_version\":\"0.1\",\"verdict\":\"approved\",\"findings\":[]}," +
        "\"code_review_receipt\":{\"contract_version\":\"0.1\",\"verdict\":\"approved\",\"findings\":[]}," +
        "\"unit_test_value_receipt\":{\"contract_version\":\"0.1\",\"verdict\":\"approved\",\"findings\":[]}," +
        "\"completeness_audit_receipt\":{\"contract_version\":\"0.1\",\"verdict\":\"approved\",\"findings\":[]}" +
        "}",
      "--format",
      "json",
    )
    assertCompactUpdate(
      payload = update,
      stepId = "verdict",
      artifactKeys = listOf(
        "code_review_receipt",
        "completeness_audit_receipt",
        "diff_projection",
        "feature_flag_audit_receipt",
        "unit_test_value_receipt",
      ),
      readOnlyCommand = "skill-bill --db '$dbPath' verify-workflow show '$workflowId' --format json",
    )
  }
}

private fun assertCompactUpdate(
  payload: Map<String, Any?>,
  stepId: String,
  artifactKeys: List<String>,
  readOnlyCommand: String,
) {
  assertEquals("ok", payload["status"])
  assertEquals(stepId, payload["current_step_id"])
  assertEquals(listOf(stepId), payload["updated_step_ids"])
  assertEquals(artifactKeys, payload["updated_artifact_keys"])
  assertEquals(readOnlyCommand, payload["read_only_full_state_command"])
  assertTrue(payload.containsKey("read_only_full_state_guidance"))
  assertFalse(payload.containsKey("artifacts"))
  assertFalse(payload.containsKey("steps"))
}

private fun runJson(vararg arguments: String): Map<String, Any?> {
  val result = CliRuntime.run(arguments.toList())
  assertEquals(0, result.exitCode, result.stdout)
  return decodeJsonObject(result.stdout)
}

private fun decodeJsonObject(rawJson: String): Map<String, Any?> {
  val parsed = JsonSupport.parseObjectOrNull(rawJson)
  require(parsed != null) { "Expected JSON object but got: $rawJson" }
  val decoded = JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
  require(decoded != null) { "Expected decoded JSON object but got: $rawJson" }
  return decoded
}

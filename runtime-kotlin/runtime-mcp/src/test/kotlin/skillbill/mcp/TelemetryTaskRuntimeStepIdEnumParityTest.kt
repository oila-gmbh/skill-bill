package skillbill.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.mcp.telemetry.TelemetryEventSchemaPaths
import skillbill.testing.repoRootFromTest
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-013: single-source the EXPERIMENTAL feature-task-runtime phase step-id enum. The telemetry
 * schema's `$defs/workflowTaskRuntimeStepIdEnum` MUST mirror
 * `FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds` — the same definition
 * `McpToolRegistry` derives the MCP update step-id enum from — so the hard-coded YAML enum cannot
 * silently drift from the runtime. Order-sensitive: the schema enum lists phases in DAG order.
 */
class TelemetryTaskRuntimeStepIdEnumParityTest {
  @Test
  fun `telemetry task-runtime step-id enum equals the workflow definition step ids`() {
    val schemaFile = repoRootFromTest().resolve(TelemetryEventSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")

    val schema: JsonNode = YAMLMapper().readTree(Files.readString(schemaFile))
    val enumNode = schema.path("\$defs").path("workflowTaskRuntimeStepIdEnum").path("enum")
    assertTrue(enumNode.isArray, "workflowTaskRuntimeStepIdEnum.enum must be an array; found: $enumNode")

    val schemaStepIds = enumNode.map { it.asText() }
    assertEquals(
      FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds,
      schemaStepIds,
      "Telemetry workflowTaskRuntimeStepIdEnum must equal " +
        "FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds, in order.",
    )
  }
}

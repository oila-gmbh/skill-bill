package skillbill.mcp

import skillbill.mcp.core.McpToolRegistry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import kotlin.test.Test
import kotlin.test.assertEquals

class FailureDispositionSchemaAdvertisementTest {
  @Test
  fun `phase_block advertises the settlement failure_disposition enum`() {
    val schema = McpToolRegistry.toolNamed("feature_task_phase_block")
      ?.inputSchema
      ?.let { it["properties"] as? Map<*, *> }
      ?.get("failure_disposition") as? Map<*, *>

    assertEquals(
      FeatureTaskRuntimeFailureDisposition.entries.map(FeatureTaskRuntimeFailureDisposition::wireValue),
      schema?.get("enum"),
      "feature_task_phase_block must advertise the settlement dispositions; an unconstrained " +
        "string lets an invented value block the run at the settlement gate instead.",
    )
  }
}

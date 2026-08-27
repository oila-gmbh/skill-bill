package skillbill.contracts.workflow

import org.yaml.snakeyaml.Yaml
import skillbill.testing.repoRootFromTest
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimeProjectionCanonicalizationSchemaRepoTest {
  @Test
  fun `the schema literal completed_task_ids cap equals the Kotlin projection list cap`() {
    val schema = Yaml().load<Map<String, Any?>>(
      Files.readString(
        repoRootFromTest().resolve("orchestration/contracts/feature-task-runtime-planning-projections-schema.yaml"),
      ),
    )
    val receipt = (schema["\$defs"] as Map<*, *>)["implementation_receipt"] as Map<*, *>
    val completedTaskIds = (receipt["properties"] as Map<*, *>)["completed_task_ids"] as Map<*, *>
    val maxItems = (completedTaskIds["maxItems"] as? Number)?.toInt()

    assertEquals(FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT, maxItems)
  }
}

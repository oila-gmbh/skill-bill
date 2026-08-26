package skillbill.contracts.workflow

import org.yaml.snakeyaml.Yaml
import skillbill.testing.repoRootFromTest
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimeProjectionCanonicalizationSchemaRepoTest {
  @Test
  fun `the schema literal task cap equals the Kotlin projection list cap`() {
    val schema = Yaml().load<Map<String, Any?>>(
      Files.readString(
        repoRootFromTest().resolve("orchestration/contracts/feature-task-runtime-planning-projections-schema.yaml"),
      ),
    )
    val plan = (schema["\$defs"] as Map<*, *>)["executable_plan"] as Map<*, *>
    val tasks = (plan["properties"] as Map<*, *>)["tasks"] as Map<*, *>
    val maxItems = (tasks["maxItems"] as? Number)?.toInt()

    assertEquals(FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT, maxItems)
  }
}

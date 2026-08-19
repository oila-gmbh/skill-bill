package skillbill.contracts.workflow

import org.yaml.snakeyaml.Yaml
import skillbill.testing.repoRootFromTest
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_SELECTED_BOUNDARY_HEADING_MAX_COUNT
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimeProjectionCanonicalizationSchemaRepoTest {
  // The schema's list-cap-budget-agreement check names the constant in prose; only this binds it.
  @Test
  fun `the schema literal cap equals the Kotlin selected boundary heading cap`() {
    val schema = Yaml().load<Map<String, Any?>>(
      Files.readString(
        repoRootFromTest().resolve("orchestration/contracts/feature-task-runtime-planning-projections-schema.yaml"),
      ),
    )
    val digest = (schema["\$defs"] as Map<*, *>)["preplanning_digest"] as Map<*, *>
    val headings = (digest["properties"] as Map<*, *>)["selected_boundary_headings"] as Map<*, *>

    assertEquals(FEATURE_TASK_RUNTIME_SELECTED_BOUNDARY_HEADING_MAX_COUNT, headings["maxItems"])
  }
}

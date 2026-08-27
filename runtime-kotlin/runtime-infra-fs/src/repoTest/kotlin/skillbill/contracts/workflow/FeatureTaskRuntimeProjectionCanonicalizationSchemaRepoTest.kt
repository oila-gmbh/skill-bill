package skillbill.contracts.workflow

import org.yaml.snakeyaml.Yaml
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FeatureTaskRuntimeProjectionCanonicalizationSchemaRepoTest {
  @Test
  fun `planning projections schema is reject-all with no live implementation_receipt def`() {
    val schema = Yaml().load<Map<String, Any?>>(
      Files.readString(
        repoRootFromTest().resolve("orchestration/contracts/feature-task-runtime-planning-projections-schema.yaml"),
      ),
    )
    val defs = schema["\$defs"] as Map<*, *>
    assertFalse(
      defs.containsKey("implementation_receipt"),
      "implementation_receipt must not remain a live planning-projection variant",
    )
    assertEquals(emptyList<Any?>(), schema["oneOf"] as List<*>)
  }
}

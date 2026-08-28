package skillbill.contracts.workflow

import org.yaml.snakeyaml.Yaml
import skillbill.contracts.goalplanning.GoalVerificationBoundaryCaps
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeVerifyFindingsDispositionSchemaRepoTest {
  @Test
  fun `verify_findings disposition schema requires only finding_id and disposition on census items`() {
    val schema = Yaml().load<Map<String, Any?>>(schemaText())
    val allOf = schema["allOf"] as List<*>
    val verifyFindingsBranch = allOf.mapNotNull { entry ->
      entry as? Map<*, *>
    }.firstOrNull { branch ->
      val condition = branch["if"] as? Map<*, *> ?: return@firstOrNull false
      val phaseId = (condition["properties"] as? Map<*, *>)?.get("phase_id") as? Map<*, *>
      phaseId?.get("const") == "verify_findings"
    }
    assertNotNull(verifyFindingsBranch, "verify_findings conditional branch must exist in phase output schema")

    val dispositionItems = (
      (
        (
          ((verifyFindingsBranch["then"] as Map<*, *>)["properties"] as Map<*, *>)
            ["produced_outputs"] as Map<*, *>
          )["properties"] as Map<*, *>
        )
        ["finding_dispositions"] as Map<*, *>
      )["items"] as Map<*, *>
    val required = dispositionItems["required"] as List<*>
    assertEquals(listOf("finding_id", "disposition"), required)
    assertTrue(dispositionItems["additionalProperties"] != false)
  }

  @Test
  fun `verify_findings disposition schema admits provenance fields and caps selections to verification bodies`() {
    val schema = Yaml().load<Map<String, Any?>>(schemaText())
    val allOf = schema["allOf"] as List<*>
    val verifyFindingsBranch = allOf.mapNotNull { entry ->
      entry as? Map<*, *>
    }.firstOrNull { branch ->
      val condition = branch["if"] as? Map<*, *> ?: return@firstOrNull false
      val phaseId = (condition["properties"] as? Map<*, *>)?.get("phase_id") as? Map<*, *>
      phaseId?.get("const") == "verify_findings"
    }
    assertNotNull(verifyFindingsBranch, "verify_findings conditional branch must exist in phase output schema")

    val dispositionItems = (
      (
        (
          ((verifyFindingsBranch["then"] as Map<*, *>)["properties"] as Map<*, *>)
            ["produced_outputs"] as Map<*, *>
          )["properties"] as Map<*, *>
        )
        ["finding_dispositions"] as Map<*, *>
      )["items"] as Map<*, *>
    val properties = dispositionItems["properties"] as Map<*, *>
    assertTrue(properties.containsKey("boundary_context_unavailable"))
    val selectedHeadings = properties["selected_boundary_headings"] as Map<*, *>
    assertEquals(GoalVerificationBoundaryCaps.maxSelectedBodies, selectedHeadings["maxItems"])
    val provenance = (selectedHeadings["items"] as Map<*, *>)["\$ref"] as String
    assertEquals("#/\$defs/verificationBoundaryHeadingProvenance", provenance)
  }

  private fun schemaText(): String = Files.readString(
    repoRootFromTest().resolve("orchestration/contracts/feature-task-runtime-phase-output-schema.yaml"),
  )
}

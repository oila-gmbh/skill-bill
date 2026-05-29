package skillbill.contracts.install

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SKILL-48 Subtask 2b AC2: pins `contract_version` parity between the
 * canonical schema file (`orchestration/contracts/install-plan-schema.yaml`)
 * loaded from the classpath bundle and the runtime constant
 * [INSTALL_PLAN_CONTRACT_VERSION]. Bumping one without the other is a
 * build break, by design.
 *
 * Mirrors `WorkflowStateSchemaContractVersionTest` but lives in
 * `runtime-domain` so we can read the classpath resource the validator
 * loads, instead of resolving the on-disk path (which would require the
 * repo-root testing helper).
 */
class InstallPlanSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches INSTALL_PLAN_CONTRACT_VERSION`() {
    val resourceStream = InstallPlanSchemaValidator::class.java.classLoader
      .getResourceAsStream(InstallPlanSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical install-plan schema is missing from the classpath at " +
        "'${InstallPlanSchemaPaths.CLASSPATH_RESOURCE}'. " +
        "Ensure `copyInstallPlanSchema` ran before this test.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    val schema: JsonNode = YAMLMapper().readTree(yamlText)
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")
    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      INSTALL_PLAN_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema contract_version.const must equal INSTALL_PLAN_CONTRACT_VERSION " +
        "($INSTALL_PLAN_CONTRACT_VERSION).",
    )
  }

  @Test
  fun `schema id matches InstallPlanSchemaPaths EXPECTED_SCHEMA_ID`() {
    val resourceStream = InstallPlanSchemaValidator::class.java.classLoader
      .getResourceAsStream(InstallPlanSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical install-plan schema is missing from the classpath at " +
        "'${InstallPlanSchemaPaths.CLASSPATH_RESOURCE}'.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    val schema: JsonNode = YAMLMapper().readTree(yamlText)
    val idNode = schema.path("\$id")
    assertTrue(!idNode.isMissingNode && idNode.isTextual, "Schema must declare a textual `\$id`.")
    assertEquals(
      InstallPlanSchemaPaths.EXPECTED_SCHEMA_ID,
      idNode.asText(),
      "Schema `\$id` must equal InstallPlanSchemaPaths.EXPECTED_SCHEMA_ID.",
    )
  }
}

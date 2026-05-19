package skillbill.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SKILL-48 Subtask 2d AC3: pins `contract_version` parity between the
 * canonical schema file (`orchestration/contracts/telemetry-event-schema.yaml`)
 * and the runtime constant `TELEMETRY_EVENT_CONTRACT_VERSION`. Bumping
 * one without the other is a build break, by design.
 *
 * Mirrors `InstallPlanSchemaContractVersionTest` (Subtask 2b) and
 * `WorkflowStateSchemaContractVersionTest` (Subtask 2a).
 */
class TelemetryEventSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches TELEMETRY_EVENT_CONTRACT_VERSION`() {
    val schemaFile = repoRootFromTest().resolve(TelemetryEventSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")

    val schema: JsonNode = YAMLMapper().readTree(Files.readString(schemaFile))
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")
    assertNotNull(
      contractVersionNode.takeIf { !it.isMissingNode && it.isTextual },
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      TELEMETRY_EVENT_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema contract_version.const must equal TELEMETRY_EVENT_CONTRACT_VERSION " +
        "($TELEMETRY_EVENT_CONTRACT_VERSION).",
    )
  }

  /**
   * Every per-event branch under `$defs/<event>Event` re-states
   * `contract_version` as a const so a single branch is self-contained
   * when read in isolation. Pin all branches to the runtime constant
   * so a future schema author cannot bump only some branches.
   */
  @Test
  fun `every per-event branch pins contract_version to TELEMETRY_EVENT_CONTRACT_VERSION`() {
    val schemaFile = repoRootFromTest().resolve(TelemetryEventSchemaPaths.REPO_RELATIVE_PATH)
    val schema: JsonNode = YAMLMapper().readTree(Files.readString(schemaFile))
    val defs = schema.path("\$defs")
    assertTrue(defs.isObject, "Schema \$defs must be an object.")

    defs.fields().forEach { (defName, defNode) ->
      if (!defName.endsWith("Event")) {
        // Skip shared enum/shape `$defs` entries; only per-event
        // branches carry a `contract_version` const.
        return@forEach
      }
      val branchConst = defNode.path("properties").path("contract_version").path("const")
      assertTrue(
        !branchConst.isMissingNode && branchConst.isTextual,
        "Branch '$defName' must pin properties.contract_version.const as a string; found: $branchConst",
      )
      assertEquals(
        TELEMETRY_EVENT_CONTRACT_VERSION,
        branchConst.asText(),
        "Branch '$defName' contract_version.const must equal TELEMETRY_EVENT_CONTRACT_VERSION " +
          "($TELEMETRY_EVENT_CONTRACT_VERSION).",
      )
    }
  }
}

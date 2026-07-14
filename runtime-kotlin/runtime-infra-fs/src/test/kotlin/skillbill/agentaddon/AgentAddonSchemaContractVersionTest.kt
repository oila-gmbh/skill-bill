package skillbill.agentaddon

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.agentaddon.AGENT_ADDON_CONTRACT_VERSION
import skillbill.contracts.agentaddon.AgentAddonSchemaPaths
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentAddonSchemaContractVersionTest {
  @Test
  fun `Kotlin contract version matches canonical schema`() {
    val stream = checkNotNull(
      javaClass.classLoader.getResourceAsStream(AgentAddonSchemaPaths.CLASSPATH_RESOURCE),
    )
    val schema = stream.use { YAMLMapper().readTree(it) }

    val schemaVersion = schema.path("properties").path("contract_version").path("const").asText()
    assertEquals(AGENT_ADDON_CONTRACT_VERSION, schemaVersion)
  }
}

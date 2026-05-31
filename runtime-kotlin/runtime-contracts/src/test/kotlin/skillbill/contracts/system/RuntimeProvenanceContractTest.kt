package skillbill.contracts.system

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeProvenanceContractTest {
  @Test
  fun `version contract maps runtime provenance with default build id`() {
    val contract = VersionContract(version = "1.2.3").toRuntimeProvenance("/tmp/runtime-cli")

    assertEquals("/tmp/runtime-cli", contract.executablePath)
    assertEquals("1.2.3", contract.version)
    assertEquals("1.2.3", contract.buildId)
    assertEquals(
      linkedMapOf(
        "executable_path" to "/tmp/runtime-cli",
        "version" to "1.2.3",
        "build_id" to "1.2.3",
      ),
      contract.toPayload(),
    )
  }
}

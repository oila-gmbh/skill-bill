package skillbill.contracts.workflow

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProducerOutputEvidenceContractVersionTest {
  @Test
  fun `schema and Kotlin contract versions match`() {
    val schema = Files.readString(
      producerEvidenceRepoRoot().resolve(ProducerOutputEvidenceSchemaPaths.REPOSITORY_PATH),
    )

    assertTrue("additionalProperties: false" in schema)
    val version = Regex("""const:\s*"([^"]+)"""").find(schema)?.groupValues?.get(1)
    assertEquals(PRODUCER_OUTPUT_EVIDENCE_CONTRACT_VERSION, version)
  }

  @Test
  fun `schema never validates raw payload bytes`() {
    val schema = Files.readString(
      producerEvidenceRepoRoot().resolve(ProducerOutputEvidenceSchemaPaths.REPOSITORY_PATH),
    )

    assertTrue("payload" !in schema)
  }
}

private fun producerEvidenceRepoRoot(): Path {
  var current = Path.of("").toAbsolutePath().normalize()
  while (current.parent != null) {
    val hasSettings = Files.isRegularFile(current.resolve("runtime-kotlin/settings.gradle.kts"))
    val hasContracts = Files.isDirectory(current.resolve("orchestration/contracts"))
    if (hasSettings && hasContracts) return current
    current = current.parent
  }
  error("Could not locate skill-bill repo root from ${Path.of("").toAbsolutePath().normalize()}")
}

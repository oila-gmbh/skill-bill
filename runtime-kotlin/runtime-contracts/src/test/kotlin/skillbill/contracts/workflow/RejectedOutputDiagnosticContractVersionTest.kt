package skillbill.contracts.workflow

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RejectedOutputDiagnosticContractVersionTest {
  @Test
  fun `schema and Kotlin contract versions match`() {
    val schema = Files.readString(
      Path.of("..", RejectedOutputDiagnosticSchemaPaths.REPOSITORY_PATH).normalize(),
    )

    assertTrue("additionalProperties: false" in schema)
    val version = Regex("""const:\s*"([^"]+)"""").find(schema)?.groupValues?.get(1)
    assertEquals(REJECTED_OUTPUT_DIAGNOSTIC_CONTRACT_VERSION, version)
  }
}

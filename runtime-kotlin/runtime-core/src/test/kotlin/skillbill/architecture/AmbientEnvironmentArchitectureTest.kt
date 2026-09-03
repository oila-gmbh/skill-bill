package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class AmbientEnvironmentArchitectureTest {
  @Test
  fun `runtime-cli ambient environment sites equal the recorded census`() {
    val baseline = ArchitectureScanSupport.parseStringSetBaseline(
      ArchitectureBaselineSupport.readBaseline("runtime-cli-ambient-environment-baseline.txt"),
    )
    val current = ArchitectureScanSupport.ambientEnvironmentCallSites(PrincipleEnforcementInventory.RUNTIME_CLI_MAIN)
      .map { site -> ArchitectureScanSupport.encodeAmbientSite(site) }
      .toSet()
    assertEquals(
      baseline,
      current,
      "Re-record runtime-cli-ambient-environment-baseline.txt with RECORD_ARCHITECTURE_BASELINES=1.",
    )
  }

  @Test
  fun `ambient environment scanner fires on every unlisted banned form`() {
    val source = """
      package skillbill.example

      import java.nio.file.Path
      import java.nio.file.Paths

      val home = System.getenv("HOME")
      val configured = System.getProperty("user.home")
      val workingDir = Path.of("")
      val legacyWorkingDir = Paths.get("")
    """.trimIndent()
    val violations = ArchitectureScanSupport.ambientEnvironmentViolationsInSource(
      relativePath = EXAMPLE_PATH,
      source = source,
      baseline = emptySet(),
    )
    assertEquals(
      listOf(
        "$EXAMPLE_PATH:6:System.getenv() is not listed in the ambient-environment baseline.",
        "$EXAMPLE_PATH:7:System.getProperty() is not listed in the ambient-environment baseline.",
        "$EXAMPLE_PATH:8:Path.of(\"\") is not listed in the ambient-environment baseline.",
        "$EXAMPLE_PATH:9:Paths.get(\"\") is not listed in the ambient-environment baseline.",
      ),
      violations,
    )
  }

  private companion object {
    const val EXAMPLE_PATH = "runtime-kotlin/runtime-example/src/main/kotlin/Example.kt"
  }
}

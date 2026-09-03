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
  fun `runtime-application ambient environment sites equal the recorded census`() {
    assertAmbientEnvironmentMatchesBaseline("runtime-application")
  }

  @Test
  fun `runtime-contracts ambient environment sites equal the recorded census`() {
    assertAmbientEnvironmentMatchesBaseline("runtime-contracts")
  }

  @Test
  fun `runtime-core ambient environment sites equal the recorded census`() {
    assertAmbientEnvironmentMatchesBaseline("runtime-core")
  }

  @Test
  fun `runtime-domain ambient environment sites equal the recorded census`() {
    assertAmbientEnvironmentMatchesBaseline("runtime-domain")
  }

  @Test
  fun `runtime-infra-fs ambient environment sites equal the recorded census`() {
    assertAmbientEnvironmentMatchesBaseline("runtime-infra-fs")
  }

  @Test
  fun `runtime-infra-http ambient environment sites equal the recorded census`() {
    assertAmbientEnvironmentMatchesBaseline("runtime-infra-http")
  }

  @Test
  fun `runtime-infra-sqlite ambient environment sites equal the recorded census`() {
    assertAmbientEnvironmentMatchesBaseline("runtime-infra-sqlite")
  }

  @Test
  fun `runtime-mcp ambient environment sites equal the recorded census`() {
    assertAmbientEnvironmentMatchesBaseline("runtime-mcp")
  }

  @Test
  fun `runtime-ports ambient environment sites equal the recorded census`() {
    assertAmbientEnvironmentMatchesBaseline("runtime-ports")
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

  private fun assertAmbientEnvironmentMatchesBaseline(moduleName: String) {
    val scanCase = PrincipleEnforcementInventory.moduleArchitectureScanCases
      .single { scanCase -> scanCase.moduleName == moduleName }
    val baseline = ArchitectureScanSupport.parseStringSetBaseline(
      ArchitectureBaselineSupport.readBaseline(scanCase.ambientEnvironmentBaseline),
    )
    val current = ArchitectureScanSupport.ambientEnvironmentCallSites(scanCase.mainScanRoot)
      .map { site -> ArchitectureScanSupport.encodeAmbientSite(site) }
      .toSet()
    assertEquals(
      baseline,
      current,
      "Re-record ${scanCase.ambientEnvironmentBaseline} with RECORD_ARCHITECTURE_BASELINES=1.",
    )
  }

  private companion object {
    const val EXAMPLE_PATH = "runtime-kotlin/runtime-example/src/main/kotlin/Example.kt"
  }
}

package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeApplicationAmbientClockArchitectureTest {
  @Test
  fun `runtime-application main matches the ambient clock baseline`() {
    val violations = ArchitectureScanSupport.ambientClockViolations(
      baseline = baseline("runtime-application-ambient-clock-baseline.txt"),
      scanRoot = PrincipleEnforcementInventory.RUNTIME_APPLICATION_MAIN,
    )
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `runtime-cli ambient clock sites equal the recorded census`() {
    val current = ArchitectureScanSupport.ambientClockCallSites(PrincipleEnforcementInventory.RUNTIME_CLI_MAIN)
      .map { site -> ArchitectureScanSupport.encodeAmbientSite(site) }
      .toSet()
    assertEquals(
      baseline("runtime-cli-ambient-clock-baseline.txt"),
      current,
      "Re-record runtime-cli-ambient-clock-baseline.txt with RECORD_ARCHITECTURE_BASELINES=1.",
    )
  }

  @Test
  fun `runtime-contracts ambient clock sites equal the recorded census`() {
    assertAmbientClockMatchesBaseline("runtime-contracts")
  }

  @Test
  fun `runtime-core ambient clock sites equal the recorded census`() {
    assertAmbientClockMatchesBaseline("runtime-core")
  }

  @Test
  fun `runtime-domain ambient clock sites equal the recorded census`() {
    assertAmbientClockMatchesBaseline("runtime-domain")
  }

  @Test
  fun `runtime-infra-fs ambient clock sites equal the recorded census`() {
    assertAmbientClockMatchesBaseline("runtime-infra-fs")
  }

  @Test
  fun `runtime-infra-http ambient clock sites equal the recorded census`() {
    assertAmbientClockMatchesBaseline("runtime-infra-http")
  }

  @Test
  fun `runtime-infra-sqlite ambient clock sites equal the recorded census`() {
    assertAmbientClockMatchesBaseline("runtime-infra-sqlite")
  }

  @Test
  fun `runtime-mcp ambient clock sites equal the recorded census`() {
    assertAmbientClockMatchesBaseline("runtime-mcp")
  }

  @Test
  fun `runtime-ports ambient clock sites equal the recorded census`() {
    assertAmbientClockMatchesBaseline("runtime-ports")
  }

  @Test
  fun `ambient clock scanner fires on unlisted Instant now site`() {
    val source = """
      package skillbill.example

      import java.time.Instant

      fun nowMarker() = Instant.now()
    """.trimIndent()
    val violations = ArchitectureScanSupport.ambientClockViolationsInSource(
      relativePath = EXAMPLE_PATH,
      source = source,
      baseline = emptySet(),
    )
    assertEquals(
      listOf("$EXAMPLE_PATH:5:Instant.now() is not listed in the ambient-clock baseline."),
      violations,
    )
  }

  @Test
  fun `ambient clock scanner fires on unlisted LocalDate now site`() {
    val source = """
      package skillbill.example

      import java.time.LocalDate

      fun todayMarker() = LocalDate.now()
    """.trimIndent()
    val violations = ArchitectureScanSupport.ambientClockViolationsInSource(
      relativePath = EXAMPLE_PATH,
      source = source,
      baseline = emptySet(),
    )
    assertEquals(
      listOf("$EXAMPLE_PATH:5:LocalDate.now() is not listed in the ambient-clock baseline."),
      violations,
    )
  }

  private fun assertAmbientClockMatchesBaseline(moduleName: String) {
    val scanCase = PrincipleEnforcementInventory.moduleArchitectureScanCases
      .single { scanCase -> scanCase.moduleName == moduleName }
    val current = ArchitectureScanSupport.ambientClockCallSites(scanCase.mainScanRoot)
      .map { site -> ArchitectureScanSupport.encodeAmbientSite(site) }
      .toSet()
    assertEquals(
      baseline(scanCase.ambientClockBaseline),
      current,
      "Re-record ${scanCase.ambientClockBaseline} with RECORD_ARCHITECTURE_BASELINES=1.",
    )
  }

  private fun baseline(name: String): Set<String> =
    ArchitectureScanSupport.parseStringSetBaseline(ArchitectureBaselineSupport.readBaseline(name))

  private companion object {
    const val EXAMPLE_PATH = "runtime-kotlin/runtime-example/src/main/kotlin/Example.kt"
  }
}

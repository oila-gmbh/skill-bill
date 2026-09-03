package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationPackageAcyclicityArchitectureTest {
  @Test
  fun `application package cycles match the recorded baseline`() {
    val violations = ArchitectureScanSupport.packageCycleViolations(
      baselineCycles = baselineCycles("application-package-cycle-baseline.txt"),
      scanRoot = PrincipleEnforcementInventory.RUNTIME_APPLICATION_MAIN,
      packagePrefix = PrincipleEnforcementInventory.APPLICATION_PACKAGE_PREFIX,
    )
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `runtime-cli package cycles equal the recorded census`() {
    val current = ArchitectureScanSupport.packageCycles(
      scanRoot = PrincipleEnforcementInventory.RUNTIME_CLI_MAIN,
      packagePrefix = PrincipleEnforcementInventory.CLI_PACKAGE_PREFIX,
    )
    assertEquals(
      baselineCycles("runtime-cli-package-cycle-baseline.txt"),
      current,
      "Re-record runtime-cli-package-cycle-baseline.txt with RECORD_ARCHITECTURE_BASELINES=1.",
    )
  }

  @Test
  fun `package cycle scanner fires on synthetic cycle absent from baseline`() {
    val violations = ArchitectureScanSupport.packageCycleViolationsForEdges(
      edges = mapOf(
        "alpha" to setOf("beta"),
        "beta" to setOf("alpha"),
      ),
      baselineCycles = emptySet(),
    )
    assertEquals(
      listOf("New package cycle not in baseline: alpha <-> beta"),
      violations,
    )
    assertTrue(violations.single().contains("alpha <-> beta"))
  }

  private fun baselineCycles(name: String): Set<ArchitectureScanSupport.PackageCycle> =
    ArchitectureScanSupport.parsePackageCycleBaseline(ArchitectureBaselineSupport.readBaseline(name))
}

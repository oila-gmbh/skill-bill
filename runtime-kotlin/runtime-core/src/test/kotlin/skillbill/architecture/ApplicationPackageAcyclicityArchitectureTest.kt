package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationPackageAcyclicityArchitectureTest {
  @Test
  fun `application package cycles match the recorded baseline`() {
    val baseline = ArchitectureScanSupport.parsePackageCycleBaseline(
      ArchitectureBaselineSupport.readBaseline("application-package-cycle-baseline.txt"),
    )
    val violations = ArchitectureScanSupport.applicationPackageCycleViolations(baseline)
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
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
}

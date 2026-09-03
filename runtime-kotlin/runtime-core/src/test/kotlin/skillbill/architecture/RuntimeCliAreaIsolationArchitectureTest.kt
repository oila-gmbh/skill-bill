package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeCliAreaIsolationArchitectureTest {
  @Test
  fun `every runtime-cli command area imports only the shared kernel and model leaves`() {
    val violations = ArchitectureScanSupport.areaIsolationViolations(
      scanRoot = PrincipleEnforcementInventory.RUNTIME_CLI_MAIN,
      packagePrefix = PrincipleEnforcementInventory.CLI_PACKAGE_PREFIX,
      sharedAreas = PrincipleEnforcementInventory.cliSharedLeafAreas,
      compositionRootArea = PrincipleEnforcementInventory.CLI_COMPOSITION_ROOT_AREA,
    )
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `all-area scan fires on a one-directional sibling edge an empty cycle baseline accepts`() {
    val edges = mapOf(
      "core" to setOf("goal", "featuretask"),
      "goal" to setOf("featuretask", "kernel"),
      "featuretask" to setOf("kernel", "model"),
    )
    assertEquals(
      emptyList(),
      ArchitectureScanSupport.packageCycleViolationsForEdges(edges, baselineCycles = emptySet()),
    )
    assertEquals(
      listOf(
        "Area 'goal' transitively imports 'featuretask'; only the shared leaves (kernel, model) may appear " +
          "in a single area's import closure.",
      ),
      ArchitectureScanSupport.allAreaIsolationViolationsForEdges(
        edges = edges,
        sharedAreas = setOf("kernel", "model"),
        compositionRootArea = "core",
      ),
    )
  }

  @Test
  fun `area isolation scanner fires on a synthetic hub reached through one edge`() {
    val violations = ArchitectureScanSupport.areaIsolationViolationsForEdges(
      edges = mapOf(
        "alpha" to setOf("hub"),
        "hub" to setOf("beta"),
      ),
      area = "alpha",
      sharedAreas = setOf("kernel", "model"),
    )
    assertEquals(
      listOf(
        "Area 'alpha' transitively imports 'beta'; only the shared leaves (kernel, model) may appear " +
          "in a single area's import closure.",
        "Area 'alpha' transitively imports 'hub'; only the shared leaves (kernel, model) may appear " +
          "in a single area's import closure.",
      ),
      violations,
    )
  }

  @Test
  fun `area isolation scanner accepts a closure of shared leaves only`() {
    val violations = ArchitectureScanSupport.areaIsolationViolationsForEdges(
      edges = mapOf(
        "alpha" to setOf("kernel", "model"),
        "kernel" to setOf("model"),
      ),
      area = "alpha",
      sharedAreas = setOf("kernel", "model"),
    )
    assertEquals(emptyList(), violations)
  }
}

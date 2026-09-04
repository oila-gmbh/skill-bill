package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeSpilloverFileNameArchitectureTest {
  @Test
  fun `spillover filename census equals the recorded baseline`() {
    val baseline = ArchitectureScanSupport.parseStringSetBaseline(
      ArchitectureBaselineSupport.readBaseline(PrincipleEnforcementInventory.SPILLOVER_FILE_NAME_BASELINE),
    )
    val scanRoots = PrincipleEnforcementInventory.moduleArchitectureScanCases.map { scanCase ->
      scanCase.moduleSourceRoot
    }
    val current = ArchitectureScanSupport.spilloverFileNamePaths(
      scanRoots = scanRoots,
      exemptPaths = PrincipleEnforcementInventory.spilloverFileNameExemptions,
    )
    assertEquals(
      baseline,
      current,
      "Re-record ${PrincipleEnforcementInventory.SPILLOVER_FILE_NAME_BASELINE} with RECORD_ARCHITECTURE_BASELINES=1.",
    )
  }

  @Test
  fun `spillover filename violations minus baseline stay empty`() {
    val baseline = ArchitectureScanSupport.parseStringSetBaseline(
      ArchitectureBaselineSupport.readBaseline(PrincipleEnforcementInventory.SPILLOVER_FILE_NAME_BASELINE),
    )
    val scanRoots = PrincipleEnforcementInventory.moduleArchitectureScanCases.map { scanCase ->
      scanCase.moduleSourceRoot
    }
    val current = ArchitectureScanSupport.spilloverFileNamePaths(
      scanRoots = scanRoots,
      exemptPaths = PrincipleEnforcementInventory.spilloverFileNameExemptions,
    )
    val unlisted = current - baseline
    assertEquals(emptySet(), unlisted, unlisted.joinToString("\n"))
  }

  @Test
  fun `spillover filename scanner fires on synthetic suffixed names`() {
    val violations = ArchitectureScanSupport.spilloverFileNameViolationsForPaths(
      relativePaths = listOf(
        "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/Foo.kt",
        "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/FooExtras2.kt",
        "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/BarExtras.kt",
        "runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/RuntimeBootstrapBindings.kt",
        "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/FooContinued2.kt",
      ),
      exemptPaths = emptySet(),
    )
    assertEquals(
      listOf(
        "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/FooContinued2.kt carries the " +
          "spillover-filename signature; name the unit for the responsibility it holds.",
        "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/BarExtras.kt carries the " +
          "spillover-filename signature; name the unit for the responsibility it holds.",
        "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/FooExtras2.kt carries the " +
          "spillover-filename signature; name the unit for the responsibility it holds.",
      ),
      violations,
    )
  }

  @Test
  fun `spillover filename scanner ignores legitimately numbered domain names without siblings`() {
    val violations = ArchitectureScanSupport.spilloverFileNameViolationsForPaths(
      relativePaths = listOf(
        "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/Protocol2.kt",
      ),
      exemptPaths = emptySet(),
    )
    assertEquals(emptyList(), violations)
  }

  @Test
  fun `spillover filename scanner flags bare trailing digits only within the same package`() {
    val samePackageViolations = ArchitectureScanSupport.spilloverFileNameViolationsForPaths(
      relativePaths = listOf(
        "runtime-kotlin/runtime-example/src/main/kotlin/skillbill/example/Foo.kt",
        "runtime-kotlin/runtime-example/src/main/kotlin/skillbill/example/Foo2.kt",
      ),
      exemptPaths = emptySet(),
    )
    assertEquals(
      listOf(
        "runtime-kotlin/runtime-example/src/main/kotlin/skillbill/example/Foo2.kt carries the " +
          "spillover-filename signature; name the unit for the responsibility it holds.",
      ),
      samePackageViolations,
    )
    val crossPackageViolations = ArchitectureScanSupport.spilloverFileNameViolationsForPaths(
      relativePaths = listOf(
        "runtime-kotlin/runtime-example/src/main/kotlin/skillbill/other/Foo.kt",
        "runtime-kotlin/runtime-example/src/main/kotlin/skillbill/example/Foo2.kt",
      ),
      exemptPaths = emptySet(),
    )
    assertEquals(emptyList(), crossPackageViolations)
  }

  @Test
  fun `spillover filename scanner honours a named exemption`() {
    val exempt = "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/FooExtras.kt"
    val violations = ArchitectureScanSupport.spilloverFileNameViolationsForPaths(
      relativePaths = listOf(exempt),
      exemptPaths = setOf(exempt),
    )
    assertEquals(emptyList(), violations)
  }
}

package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeCliSpilloverFileNameArchitectureTest {
  @Test
  fun `no runtime-cli file carries the spillover-filename signature`() {
    val violations = ArchitectureScanSupport.spilloverFileNameViolations(
      scanRoots = listOf(PrincipleEnforcementInventory.RUNTIME_CLI_SRC),
      exemptPaths = PrincipleEnforcementInventory.spilloverFileNameExemptions,
    )
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `spillover filename scanner fires on synthetic suffixed names`() {
    val violations = ArchitectureScanSupport.spilloverFileNameViolationsForPaths(
      relativePaths = listOf(
        "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/Foo.kt",
        "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/FooExtras2.kt",
        "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/BarExtras.kt",
      ),
      exemptPaths = emptySet(),
    )
    assertEquals(
      listOf(
        "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/BarExtras.kt carries the " +
          "spillover-filename signature; name the unit for the responsibility it holds.",
        "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/FooExtras2.kt carries the " +
          "spillover-filename signature; name the unit for the responsibility it holds.",
      ),
      violations,
    )
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

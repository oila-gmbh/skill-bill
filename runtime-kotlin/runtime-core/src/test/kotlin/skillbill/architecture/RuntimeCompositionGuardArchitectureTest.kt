package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeCompositionGuardArchitectureTest {
  private val diRoot = "runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di"
  private val boundClasses = ArchitectureScanSupport.boundComponentConcreteClassNames(diRoot)
  private val scanRoots = PrincipleEnforcementInventory.moduleArchitectureScanCases.map { it.mainScanRoot }

  @Test
  fun `no main-source site outside skillbill di constructs a component-bound class`() {
    val violations = ArchitectureScanSupport.directComponentConstructionViolations(
      boundClassNames = boundClasses,
      scanRoots = scanRoots,
      compositionDiRoot = diRoot,
      sanctionedEntrypoints = PrincipleEnforcementInventory.sanctionedCompositionEntrypoints,
    )
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `composition guard fires on synthetic bound-class construction outside skillbill di`() {
    val violations = ArchitectureScanSupport.directComponentConstructionViolationsForSource(
      boundClassNames = setOf("FileTelemetryConfigStore", "TelemetryLevelMutationService"),
      relativePath = "runtime-kotlin/runtime-example/src/main/kotlin/skillbill/example/Example.kt",
      source = """
        package skillbill.example
        import skillbill.infrastructure.fs.FileTelemetryConfigStore
        class Example {
          fun leak() {
            FileTelemetryConfigStore(context)
            TelemetryLevelMutationService(database, settings, configStore)
          }
        }
      """.trimIndent(),
    )
    assertEquals(
      listOf(
        "runtime-kotlin/runtime-example/src/main/kotlin/skillbill/example/Example.kt constructs " +
          "FileTelemetryConfigStore outside skillbill.di",
        "runtime-kotlin/runtime-example/src/main/kotlin/skillbill/example/Example.kt constructs " +
          "TelemetryLevelMutationService outside skillbill.di",
      ),
      violations,
    )
  }

  @Test
  fun `composition guard ignores sanctioned second entrypoints`() {
    val violations = ArchitectureScanSupport.directComponentConstructionViolations(
      boundClassNames = setOf("FileSystemScaffoldRepoValidation", "FileSystemScaffoldSourceLoader"),
      scanRoots = listOf(
        "runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/runtime/ScaffoldStandaloneEntrypoint.kt",
      ),
      compositionDiRoot = diRoot,
      sanctionedEntrypoints = PrincipleEnforcementInventory.sanctionedCompositionEntrypoints,
    )
    assertEquals(emptyList(), violations)
  }

  @Test
  fun `bound-class census is non-empty`() {
    assertTrue(boundClasses.isNotEmpty(), "skillbill.di must declare at least one concrete bound class.")
  }
}

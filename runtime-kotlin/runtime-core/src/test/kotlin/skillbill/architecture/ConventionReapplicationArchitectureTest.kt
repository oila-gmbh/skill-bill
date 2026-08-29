package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConventionReapplicationArchitectureTest {
  @Test
  fun `module build files do not re-apply convention-owned Test and toolchain settings`() {
    val moduleBuildFiles = RuntimeModuleCatalog.declaredGradleModules
      .map { module -> ArchitectureScanSupport.runtimeRoot.resolve("runtime-kotlin/$module/build.gradle.kts") }
      .filter { path -> Files.exists(path) }
    val violations = ArchitectureScanSupport.conventionReapplicationViolations(
      moduleBuildFiles = moduleBuildFiles,
      ownedPatterns = PrincipleEnforcementInventory.conventionOwnedTestPatterns,
    )
    assertEquals(
      emptyList(),
      violations,
      "Module build files must not re-declare update-snapshots or other Test settings already owned by configureKotlinJvm.",
    )
  }

  @Test
  fun `convention reapplication scanner fires on synthetic update-snapshots snippet`() {
    val syntheticSnippet =
      """
      tasks.withType<Test>().configureEach {
        if (project.hasProperty("update-snapshots")) {
          systemProperty("update-snapshots", "true")
        }
      }
      """.trimIndent()
    val violations = ArchitectureScanSupport.conventionReapplicationViolationsInText(
      relativePath = "runtime-kotlin/runtime-example/build.gradle.kts",
      text = syntheticSnippet,
      ownedPatterns = PrincipleEnforcementInventory.conventionOwnedTestPatterns,
    )
    assertTrue(
      violations.isNotEmpty(),
      "Regression if a module build file can re-apply update-snapshots after the convention hoist.",
    )
    assertTrue(
      violations.any { violation -> violation.contains("update-snapshots") },
      "Synthetic fixture must re-declare update-snapshots to prove the convention reapplication guard.",
    )
  }
}

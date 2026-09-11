package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RuntimeSubsystemPackageRootArchitectureTest {
  @Test
  fun `each infrastructure entry engine and application module owns exactly one main package root`() {
    val violations = RuntimeModuleCatalog.moduleMainPackageRoots.mapNotNull { (moduleName, expectedRoot) ->
      val actualRoots = mainPackageRootsForModule(moduleName)
      when {
        actualRoots.isEmpty() -> "$moduleName has no main-source package root"
        actualRoots.size > 1 -> "$moduleName has multiple main-source roots: $actualRoots"
        actualRoots.single() != expectedRoot ->
          "$moduleName root ${actualRoots.single()} does not match expected $expectedRoot"
        else -> null
      }
    }
    assertEquals(
      emptyList(),
      violations,
      "Infrastructure, entry, engine, and application modules must each declare one main package root.",
    )
  }

  @Test
  fun `subsystem package root scanner rejects synthetic extra root fixture`() {
    val violation = subsystemPackageRootViolationMessage(
      moduleName = "runtime-engine",
      actualRoots = setOf("skillbill.engine", "skillbill.application.featuretask"),
      expectedRoot = "skillbill.engine",
    )
    assertNotNull(
      violation,
      "Regression if an extra subsystem root is not reported.",
    )
    assertEquals(
      "runtime-engine has multiple main-source roots: [skillbill.application.featuretask, skillbill.engine]",
      violation,
    )
  }
}

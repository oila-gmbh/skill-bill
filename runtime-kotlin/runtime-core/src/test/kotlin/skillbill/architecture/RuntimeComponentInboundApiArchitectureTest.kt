package skillbill.architecture

import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeComponentInboundApiArchitectureTest {
  @Test
  fun `RuntimeComponent abstract property set equals the pinned inbound api`() {
    val source = ArchitectureScanSupport.runtimeRoot
      .resolve(PrincipleEnforcementInventory.RUNTIME_COMPONENT_SOURCE)
      .readText()
    assertEquals(
      PrincipleEnforcementInventory.runtimeComponentInboundApi.toSet(),
      ArchitectureScanSupport.abstractPropertyNames(source),
      "RuntimeComponent's abstract property set is the runtime's inbound API; " +
        "record an intentional change in PrincipleEnforcementInventory.runtimeComponentInboundApi.",
    )
  }

  @Test
  fun `abstract property scanner reports an added abstract property`() {
    val source = """
      package skillbill.di

      abstract class RuntimeComponent {
        abstract val goalRunner: GoalRunner
        abstract val extraSurface: ExtraSurface
        fun helper(): Int = 1
      }
    """.trimIndent()
    assertEquals(
      setOf("goalRunner", "extraSurface"),
      ArchitectureScanSupport.abstractPropertyNames(source),
    )
  }
}

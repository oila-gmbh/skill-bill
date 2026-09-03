package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class PrincipleEnforcementInventoryTest {
  @Test
  fun `inventory lists fifteen enforceable rules and deliberate review-only rules`() {
    assertEquals(15, PrincipleEnforcementInventory.enforceableRules.size)
    assertEquals(4, PrincipleEnforcementInventory.reviewOnlyRules.size)
    assertEquals(10, PrincipleEnforcementInventory.parseBoundarySites.size)
    assertEquals(emptyMap(), PrincipleEnforcementInventory.productionLineCeilingExemptions)
    assertEquals(emptySet(), PrincipleEnforcementInventory.spilloverFileNameExemptions)
    assertEquals(
      setOf("runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/core/Main.kt"),
      PrincipleEnforcementInventory.ambientEnvironmentExemptions,
    )
  }
}

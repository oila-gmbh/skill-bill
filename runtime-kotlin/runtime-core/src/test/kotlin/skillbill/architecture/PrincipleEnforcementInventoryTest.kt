package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class PrincipleEnforcementInventoryTest {
  @Test
  fun `inventory lists ten enforceable rules and deliberate review-only rules`() {
    assertEquals(10, PrincipleEnforcementInventory.enforceableRules.size)
    assertEquals(4, PrincipleEnforcementInventory.reviewOnlyRules.size)
    assertEquals(10, PrincipleEnforcementInventory.parseBoundarySites.size)
    assertEquals(emptyMap(), PrincipleEnforcementInventory.productionLineCeilingExemptions)
  }
}

package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypedParseBoundaryArchitectureTest {
  @Test
  fun `named untrusted-input parse boundaries do not use forbidden malformed-input reporters`() {
    val violations = ArchitectureScanSupport.parseBoundaryViolations(
      PrincipleEnforcementInventory.parseBoundarySites,
    )
    assertEquals(
      emptyList(),
      violations,
      "Named parse boundaries must surface malformed external input through typed contract failures, not error, require, or bare throw.",
    )
  }

  @Test
  fun `typed parse boundary scanner fires on synthetic boundary fixture`() {
    val fixture =
      """
      private fun decodeBad(raw: String): String {
        val value = raw as? Map<*, *> ?: error("durable record must be an object.")
        return value.toString()
      }
      """.trimIndent()
    val violations = ArchitectureScanSupport.parseBoundaryViolationsInSource(
      source = fixture,
      site = ArchitectureScanSupport.ParseBoundarySite(
        relativePath = "Synthetic.kt",
        functionNames = setOf("decodeBad"),
      ),
    )
    assertEquals(
      listOf(
        "Synthetic.kt::decodeBad reports malformed external input via error(); use a typed contract failure instead.",
      ),
      violations,
      "Regression if a named parse boundary can report malformed external input via error, require, or bare throw.",
    )
  }
}

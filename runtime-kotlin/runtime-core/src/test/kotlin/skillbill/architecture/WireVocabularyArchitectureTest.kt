package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WireVocabularyArchitectureTest {
  @Test
  fun `runtime wire vocabulary has no duplicate declarations or local restatements`() {
    val report = WireVocabularyArchitectureSupport.scanRuntimeMainSources()
    assertEquals(
      emptyList(),
      report.violations,
      "Wire vocabulary baseline delta ${report.baselineViolationCount} -> " +
        "${report.remainingViolationCount}:\n${report.violations.joinToString("\n")}",
    )
    assertEquals(0, report.remainingViolationCount)
    assertEquals(report.baselineViolationCount, report.remainingViolationCount)
  }

  @Test
  fun `scanner accepts owners references typed subsets prose and open extension values`() {
    val files = listOf(
      syntheticSourceFile(
        "fixture/Owner.kt",
        """
        package fixture

        enum class Owner(val wireValue: String) {
          READY("ready"),

          companion object {
            fun fromWire(value: String): Owner? = when (value) {
              "old_ready" -> READY
              else -> entries.firstOrNull { it.wireValue == value }
            }
          }
        }

        object ContractKeys {
          const val STATUS = "status"
        }
        """.trimIndent(),
      ),
      syntheticSourceFile(
        "fixture/Consumer.kt",
        """
        package fixture

        import fixture.Owner as AliasOwner

        fun consume(value: AliasOwner) = value.wireValue
        fun subset(): Set<AliasOwner> = setOf(AliasOwner.READY)
        fun keyed(): Map<String, String> = mapOf(ContractKeys.STATUS to "value")
        fun prose(): String = "ready is a word in this sentence"
        fun extension(value: String): String = value
        """.trimIndent(),
      ),
    )
    val report = WireVocabularyArchitectureSupport.scanSourceFiles(files)
    assertEquals(emptyList(), report.violations)
    assertTrue(report.declarations.any { it.category == "token" && it.value == "ready" })
    assertTrue(report.declarations.any { it.category == "alias" && it.value == "old_ready" })
    assertTrue(report.declarations.any { it.category == "key" && it.value == "status" })
  }

  @Test
  fun `scanner rejects duplicates aliases local collections and foreign key accesses`() {
    val files = listOf(
      syntheticSourceFile(
        "fixture/Owner.kt",
        """
        package fixture

        enum class Owner(val wireValue: String) {
          FIRST("ready"),
          SECOND("ready"),

          companion object {
            fun fromWire(value: String): Owner? = when (value) {
              "old_ready" -> FIRST
              "old_ready" -> SECOND
              else -> null
            }
          }
        }

        object ContractKeys {
          const val STATUS = "status"
        }
        """.trimIndent(),
      ),
      syntheticSourceFile(
        "fixture/Consumer.kt",
        """
        package fixture

        @SerialName("status")
        val annotated = "value"
        val statuses = setOf("ready")
        fun consume(payload: Map<String, Any?>) = mapOf("ready" to payload["status"])
        """.trimIndent(),
      ),
    )
    val report = WireVocabularyArchitectureSupport.scanSourceFiles(files, includePayloadKeyAccesses = true)
    assertTrue(report.violations.any { it.contains("duplicate token 'ready'") })
    assertTrue(report.violations.any { it.contains("duplicate alias 'old_ready'") })
    assertTrue(report.violations.any { it.contains("restates 'ready'") })
    assertTrue(report.violations.any { it.contains("accesses key 'status'") })
    assertEquals(report.violations.sorted(), report.violations)
  }
}

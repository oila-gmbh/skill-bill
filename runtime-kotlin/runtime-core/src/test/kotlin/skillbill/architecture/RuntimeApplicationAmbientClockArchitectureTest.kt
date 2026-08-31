package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeApplicationAmbientClockArchitectureTest {
  @Test
  fun `runtime-application main matches the ambient clock baseline`() {
    val baseline = ArchitectureScanSupport.parseStringSetBaseline(
      ArchitectureBaselineSupport.readBaseline("runtime-application-ambient-clock-baseline.txt"),
    )
    val violations = ArchitectureScanSupport.ambientClockViolations(baseline)
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `ambient clock scanner fires on unlisted Instant now site`() {
    val source = """
      package skillbill.example

      import java.time.Instant

      fun nowMarker() = Instant.now()
    """.trimIndent()
    val violations = ArchitectureScanSupport.ambientClockViolationsInSource(
      relativePath = "runtime-kotlin/runtime-example/src/main/kotlin/Example.kt",
      source = source,
      baseline = emptySet(),
    )
    assertEquals(
      listOf(
        "runtime-kotlin/runtime-example/src/main/kotlin/Example.kt:5:Instant.now() " +
          "is not listed in the ambient-clock baseline.",
      ),
      violations,
    )
  }
}

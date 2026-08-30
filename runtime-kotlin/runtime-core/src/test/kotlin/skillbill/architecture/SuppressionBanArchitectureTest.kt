package skillbill.architecture

import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

class SuppressionBanArchitectureTest {
  @Test
  fun `authored suppressions and allow-list stay in bijection`() {
    val decisions = ArchitectureScanSupport.runtimeRoot.resolve("runtime-kotlin/agent/decisions.md").readText()
    val allowList = ArchitectureScanSupport.parseSuppressionAllowList(decisions)
    val suppressions = ArchitectureScanSupport.authoredSuppressions()
    val suppressionKeys = suppressions.map { Triple(it.relativePath, it.symbol, it.rule) }.toSet()
    assertEquals(
      allowList,
      suppressionKeys,
      "Allow-list rows and live suppressions must be 1:1.",
    )
  }

  @Test
  fun `live tree has no banned or unlisted compiler suppressions`() {
    val decisions = ArchitectureScanSupport.runtimeRoot.resolve("runtime-kotlin/agent/decisions.md").readText()
    val allowList = ArchitectureScanSupport.parseSuppressionAllowList(decisions)
    val violations = ArchitectureScanSupport.suppressionViolations(
      ArchitectureScanSupport.authoredSuppressions(),
      allowList,
    )
    assertEquals(
      emptyList(),
      violations,
      "Every authored @Suppress must be allow-listed; complexity-rule suppressions are never permitted.",
    )
    val detektYaml = ArchitectureScanSupport.runtimeRoot
      .resolve("runtime-kotlin/config/detekt/detekt.yml")
      .readText()
    assertEquals(
      emptyList(),
      ArchitectureScanSupport.detektComplexityPinViolations(detektYaml),
      "detekt.yml must keep every pinned complexity rule active.",
    )
  }

  @Test
  fun `suppression scanner fixture catches banned complexity suppressions`() {
    val complexityFixture =
      """
      package example

      ${"@Suppress(\"TooManyFunctions\")"}
      class Bloated {
        fun one() = 1
      }
      """.trimIndent()
    assertEquals(
      listOf("fixture.kt::Bloated uses banned complexity suppression 'TooManyFunctions'; refactor instead."),
      ArchitectureScanSupport.suppressionViolations(
        ArchitectureScanSupport.authoredSuppressionsInSource("fixture.kt", complexityFixture),
        emptySet(),
      ),
      "Regression if a TooManyFunctions suppression stops failing the complexity ban.",
    )
  }

  @Test
  fun `suppression scanner fixture catches unlisted non-complexity suppressions`() {
    val unlistedCastFixture =
      """
      package example

      class Decoder {
        ${"@Suppress(\"UNCHECKED_CAST\")"}
        fun decode(raw: Any?): Map<String, Any?> = raw as Map<String, Any?>
      }
      """.trimIndent()
    assertEquals(
      listOf(
        "fixture.kt::decode has @Suppress('UNCHECKED_CAST') without a dated allow-list row; " +
          "fix the finding or add path, symbol, rule, and why to runtime-kotlin/agent/decisions.md.",
      ),
      ArchitectureScanSupport.suppressionViolations(
        ArchitectureScanSupport.authoredSuppressionsInSource("fixture.kt", unlistedCastFixture),
        emptySet(),
      ),
      "Regression if a non-allow-listed UNCHECKED_CAST stops failing the allow-list gate.",
    )
  }

  @Test
  fun `suppression scanner fixture accepts allow-listed suppressions`() {
    val allowListFixture =
      """
      ## [2026-08-30] Compiler suppression allow-list lock

      | path | symbol | rule | why |
      | fixture.kt | decode | UNCHECKED_CAST | honest wire-map cast at decode boundary |
      """.trimIndent()
    val allowedCastFixture =
      """
      package example

      class Decoder {
        ${"@Suppress(\"UNCHECKED_CAST\")"}
        fun decode(raw: Any?): Map<String, Any?> = raw as Map<String, Any?>
      }
      """.trimIndent()
    assertEquals(
      emptyList(),
      ArchitectureScanSupport.suppressionViolations(
        ArchitectureScanSupport.authoredSuppressionsInSource("fixture.kt", allowedCastFixture),
        ArchitectureScanSupport.parseSuppressionAllowList(allowListFixture),
      ),
      "Regression if a matching allow-list row false-positives the suppression scanner.",
    )
  }

  @Test
  fun `detekt complexity pin fixture catches disabled rules`() {
    val detektRollback =
      """
      complexity:
        TooManyFunctions:
          active: false
        LargeClass:
          active: true
        LongMethod:
          active: true
        CyclomaticComplexMethod:
          active: true
        ComplexCondition:
          active: true
        NestedBlockDepth:
          active: true
        ReturnCount:
          active: true
        ThrowsCount:
          active: true
        LongParameterList:
          active: true
      """.trimIndent()
    assertEquals(
      listOf("detekt.yml must pin 'TooManyFunctions' with active: true."),
      ArchitectureScanSupport.detektComplexityPinViolations(detektRollback),
      "Regression if removing a pinned complexity key from detekt.yml stops failing the guard.",
    )
  }
}

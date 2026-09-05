package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class ProductionLogicalTypeLineCeilingArchitectureTest {
  @Test
  fun `production logical types stay within baseline or ceiling`() {
    val baseline = ArchitectureScanSupport.parseIntBaseline(
      ArchitectureBaselineSupport.readBaseline("logical-type-line-ceiling-baseline.txt"),
    )
    val violations = ArchitectureScanSupport.logicalTypeLineCeilingViolations(
      productionRoots = listOf("runtime-kotlin", "intellij-plugin"),
      ceiling = PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING,
      baseline = baseline,
    )
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `logical type ceiling scanner fires on synthetic split-file fixture`() {
    val partLineCount = PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING / 2 + 10
    val partOne = """
      package skillbill.fixture.logicaltype

      class SplitLogicalTypeFixture
    """.trimIndent() + "\n" + (1..partLineCount).joinToString("\n") { index -> "fun partOne$index() = $index" }
    val partTwo = """
      package skillbill.fixture.logicaltype

      fun SplitLogicalTypeFixture.partTwo() = Unit
    """.trimIndent() + "\n" + (1..partLineCount).joinToString("\n") { index -> "fun partTwo$index() = $index" }
    val combinedLineCount = listOf(partOne, partTwo).sumOf { source -> source.lineSequence().count() }
    val counts = linkedMapOf<String, Int>()
    listOf(partOne, partTwo).forEach { source ->
      val packageName = ArchitectureScanSupport.declaredPackage(source).orEmpty()
      val topLevelType = ArchitectureScanSupport.primaryTopLevelDeclarationName(source)
      val lineCount = source.lineSequence().count()
      val targets =
        if (topLevelType != null) {
          listOf("$packageName.$topLevelType")
        } else {
          ArchitectureScanSupport.extensionReceiverFqns(source, packageName, emptyList())
        }
      targets.forEach { fqn -> counts[fqn] = counts.getOrDefault(fqn, 0) + lineCount }
    }
    val violations = counts.mapNotNull { (fqn, lineCount) ->
      if (lineCount > PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING) {
        "$fqn has $lineCount lines; exceeds the ${PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING}-line " +
          "ceiling without a baseline entry."
      } else {
        null
      }
    }
    assertEquals(
      listOf(
        "skillbill.fixture.logicaltype.SplitLogicalTypeFixture has $combinedLineCount lines; exceeds the " +
          "${PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING}-line ceiling without a baseline entry.",
      ),
      violations,
    )
  }
}

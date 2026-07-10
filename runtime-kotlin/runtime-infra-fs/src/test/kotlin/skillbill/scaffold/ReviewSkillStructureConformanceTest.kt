package skillbill.scaffold

import skillbill.scaffold.rendering.areaReviewContent
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewSkillStructureConformanceTest {
  @Test
  fun `newly scaffolded specialists use the canonical skeleton`() {
    val content = areaReviewContent("Review security failures.", "security")

    assertEquals(
      listOf("Focus", "Ignore", "Applicability", "Project-Specific Rules"),
      content.lineSequence().filter { it.startsWith("## ") }.map { it.removePrefix("## ") }.toList(),
    )
    assertTrue("## Review Triggers" !in content)
    assertTrue("## Review Guidance" !in content)
    assertTrue("For Blocker or Major findings, describe the concrete consequence explicitly." in content)
  }

  @Test
  fun `repository pack content uses only the governed severity vocabulary`() {
    val violations = contentFiles(repoRootFromTest().resolve("platform-packs"))
      .flatMap(::severityViolations)

    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `non-exempt packs satisfy the review skill structure standard`() {
    val root = Files.createTempDirectory("review-skill-structure-")
    val pack = root.resolve("platform-packs/fixture")
    Files.createDirectories(pack.resolve("code-review/bill-fixture-code-review-security"))
    Files.writeString(
      pack.resolve("code-review/bill-fixture-code-review-security/content.md"),
      """
        ## Focus

        Focus.

        ## Ignore

        Ignore.

        ## Applicability

        Applicable.

        ## Project-Specific Rules

        ### Failure Modes

        - Check API boundaries and concrete failure modes.
        - For Blocker or Major findings, describe the concrete consequence explicitly.
      """.trimIndent(),
    )
    Files.createDirectories(pack.resolve("code-review/bill-fixture-code-review"))
    Files.writeString(
      pack.resolve("code-review/bill-fixture-code-review/content.md"),
      """
        ## Classification Rules

        Classify stack signals before review.

        ## Diff-Signal Routing Table

        Route code files to the relevant specialist.

        ## Mixed Diffs

        Exclude generated, vendored, and non-stack files.

        ## Finding Discipline

        Preserve attribution, then deduplicate overlapping findings.
      """.trimIndent(),
    )

    assertEquals(emptyList(), structureViolations(pack), structureViolations(pack).joinToString("\n"))

    Files.writeString(
      pack.resolve("code-review/bill-fixture-code-review-security/content.md"),
      "## Focus\n\nMissing the governed skeleton.\n",
    )
    assertTrue(structureViolations(pack).any { it.rule == "specialist H2 sequence" })
  }

  private fun structureViolations(pack: Path): List<StructureViolation> {
    val exemptions = setOf("go", "ios", "kmp", "kotlin", "php", "python")
    // SKILL-112 subtasks 2-7 remove one pack each; subtask 8 removes this mechanism.
    if (pack.name in exemptions) return emptyList()

    return contentFiles(pack).flatMap { file ->
      val headings = Files.readAllLines(file).filter { it.startsWith("## ") }.map { it.removePrefix("## ") }
      if (file.parent.name.endsWith("code-review")) {
        baselineViolations(file, headings)
      } else {
        specialistViolations(file, headings)
      }
    }
  }

  private fun baselineViolations(file: Path, headings: List<String>): List<StructureViolation> {
    val required = listOf("Classification Rules", "Diff-Signal Routing Table", "Mixed Diffs", "Finding Discipline")
    return if (headings == required) emptyList() else listOf(StructureViolation(file, "baseline H2 sequence"))
  }

  private fun specialistViolations(file: Path, headings: List<String>): List<StructureViolation> {
    val required = listOf("Focus", "Ignore", "Applicability", "Project-Specific Rules")
    val content = Files.readString(file)
    return buildList {
      if (headings != required && headings != required + "Repo-Local Knowledge") {
        add(StructureViolation(file, "specialist H2 sequence"))
      }
      if (!content.contains("### ")) add(StructureViolation(file, "specialist H3 grouping"))
      if (!content.contains("For Blocker or Major findings")) add(StructureViolation(file, "canonical severity closer"))
    }
  }

  private fun severityViolations(file: Path): List<StructureViolation> {
    val content = Files.readString(file)
    return buildList {
      if (Regex("(?i)\\bnit\\b").containsMatchIn(content)) add(StructureViolation(file, "forbidden severity Nit"))
      if (Regex("Critical|Major or Critical|Critical or Major|Major or Blocker").containsMatchIn(content)) {
        add(StructureViolation(file, "off-enum severity vocabulary"))
      }
    }
  }

  private fun contentFiles(root: Path): List<Path> =
    Files.walk(root).use { paths -> paths.filter { it.fileName.toString() == "content.md" }.toList() }

  private data class StructureViolation(val path: Path, val rule: String) {
    override fun toString(): String = "$path: $rule"
  }
}

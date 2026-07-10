package skillbill.scaffold

import org.yaml.snakeyaml.Yaml
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.rendering.canonicalSeverityCloser
import skillbill.scaffold.rendering.defaultAreaFocus
import skillbill.scaffold.runtime.scaffold
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ScaffoldReviewStructureAcceptanceTest {
  @Test
  fun `platform pack scaffolder emits the complete review structure`() = withReviewStructureUserHome {
    val repo = seedReviewStructureRepo()

    scaffold(reviewStructurePayload(repo, "platform-pack", "platform" to "java"))

    val pack = repo.resolve("platform-packs/java")
    assertBaseline(pack)
    assertQualityCheck(pack)
    assertNativeAgents(pack)
    val violations = ReviewSkillStructureConformanceTest().structureViolations(pack)
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      val content = Files.readString(
        pack.resolve("code-review/bill-java-code-review-$area/content.md"),
      )
      assertEquals(
        listOf("Focus", "Ignore", "Applicability", "Project-Specific Rules"),
        content.lineSequence().filter { it.startsWith("## ") }.map { it.removePrefix("## ") }.toList(),
      )
      assertContains(content, "### Review Rules")
      assertContains(content, "Verify `Java")
      assertContains(content, canonicalSeverityCloser(area))
      assertEquals(
        canonicalSeverityCloser(area),
        content.lineSequence().filter { it.startsWith("- ") }.last(),
      )
      assertRetiredHeadingsAbsent(content)
      if (area == "ui") assertUiDeferrals(content)
      if (area == "ux-accessibility") assertAccessibilityDeferrals(content)
    }
  }

  private fun assertBaseline(pack: Path) {
    val content = Files.readString(pack.resolve("code-review/bill-java-code-review/content.md"))
    assertEquals(
      listOf("Classification Rules", "Diff-Signal Routing Table", "Mixed Diffs", "Finding Discipline"),
      headings(content),
    )
    assertContains(content, "Keep the baseline specialists for the whole review")
    assertContains(content, "lightweight file-level classification")
    assertContains(content, "generated, vendored, and non-stack-owned files")
    assertContains(content, "Calibrate severity and verify each finding's preconditions")
    assertContains(content, "attributed to their specialist lane, then deduplicate")
    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      assertContains(content, "-> `$area` specialist.")
    }
  }

  private fun assertQualityCheck(pack: Path) {
    val content = Files.readString(pack.resolve("quality-check/bill-java-code-check/content.md"))
    assertEquals(listOf("Purpose", "Execution Steps", "Fix Strategy"), headings(content))
    assertContains(content, "files in scope")
    assertContains(content, "build files, wrappers, and CI configuration before falling back")
    assertContains(content, "pack's quality-check entrypoint")
    assertContains(content, "priority-ordered fix ladder and never suppress")
    assertContains(content, "Re-run targeted checks")
    assertContains(content, "full suite when targeted checks cannot establish safety")
  }

  private fun assertNativeAgents(pack: Path) {
    val agentsFile = pack.resolve("code-review/bill-java-code-review/native-agents/agents.yaml")
    val agents = (Yaml().load<Any>(Files.readString(agentsFile)) as Map<*, *>)["agents"] as List<*>
    val descriptions = agents.filterIsInstance<Map<*, *>>().associate { it["name"] to it["description"] }
    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      val description = descriptions["bill-java-code-review-$area"] as String
      assertEquals(
        "Java ${area.replace('-', ' ')} specialist code reviewer. " +
          "Runs against Java ${defaultAreaFocus(area)} across pom.xml, build.gradle, src/main/java signals. " +
          "Returns a Risk Register in the F-XXX bullet format.",
        description,
      )
    }
  }

  private fun headings(content: String): List<String> = content.lineSequence()
    .filter { it.startsWith("## ") }
    .map { it.removePrefix("## ") }
    .toList()

  private fun assertUiDeferrals(content: String) {
    assertContains(content, "Defer accessibility concerns to the ux-accessibility specialist")
    assertContains(content, "security concerns to the security specialist")
  }

  private fun assertAccessibilityDeferrals(content: String) {
    assertContains(content, "Defer UI correctness concerns to the ui specialist")
    assertContains(content, "security concerns to the security specialist")
  }

  private fun assertRetiredHeadingsAbsent(content: String) {
    listOf("## Review Guidance", "## Checklist", "## Severity Guidance").forEach { heading ->
      assertFalse(heading in content, "Retired starter heading remains: $heading")
    }
  }
}

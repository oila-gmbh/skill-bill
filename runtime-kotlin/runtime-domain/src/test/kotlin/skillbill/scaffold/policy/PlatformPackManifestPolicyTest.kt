package skillbill.scaffold.policy

import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformPackManifestPolicyTest {
  @Test
  fun `renderPlatformPackManifestContent emits the canonical platform yaml for a starter java pack`() {
    val packRoot = Path.of("/repo/platform-packs/java")
    val baselineSkillPath = packRoot.resolve("code-review").resolve("bill-java-code-review")
    val qualityCheckSkillPath = packRoot.resolve("quality-check").resolve("bill-java-code-check")

    val rendered = renderPlatformPackManifestContent(
      platform = "java",
      displayName = "Java",
      routingSignals = listOf("pom.xml", "build.gradle"),
      tieBreakers = listOf("Prefer Java"),
      specialistAreas = emptyList(),
      specialistAreaMetadata = emptyMap(),
      baselineLayers = emptyList(),
      packRoot = packRoot,
      baselineSkillPath = baselineSkillPath,
      qualityCheckSkillPath = qualityCheckSkillPath,
      specialistSkillPaths = emptyMap(),
    )

    val expected = listOf(
      "platform: \"java\"",
      "contract_version: \"1.1\"",
      "display_name: \"Java\"",
      "",
      "routing_signals:",
      "  strong:",
      "    - \"pom.xml\"",
      "    - \"build.gradle\"",
      "  tie_breakers:",
      "    - \"Prefer Java\"",
      "",
      "declared_code_review_areas: []",
      "",
      "declared_files:",
      "  baseline: \"code-review/bill-java-code-review/content.md\"",
      "  areas: {}",
      "area_metadata: {}",
      "",
      "declared_quality_check_file: \"quality-check/bill-java-code-check/content.md\"",
    ).joinToString("\n") + "\n"

    assertEquals(expected, rendered)
  }

  @Test
  fun `renderPlatformPackManifestContent appends baseline layers when provided`() {
    val packRoot = Path.of("/repo/platform-packs/java")
    val baselineSkillPath = packRoot.resolve("code-review").resolve("bill-java-code-review")
    val qualityCheckSkillPath = packRoot.resolve("quality-check").resolve("bill-java-code-check")

    val rendered = renderPlatformPackManifestContent(
      platform = "java",
      displayName = "Java",
      routingSignals = listOf("pom.xml", "build.gradle"),
      tieBreakers = emptyList(),
      specialistAreas = emptyList(),
      specialistAreaMetadata = emptyMap(),
      baselineLayers = sampleBaselineLayers(),
      packRoot = packRoot,
      baselineSkillPath = baselineSkillPath,
      qualityCheckSkillPath = qualityCheckSkillPath,
      specialistSkillPaths = emptyMap(),
    )

    assertEquals(expectedRenderingWithBaselineLayers(), rendered)
  }

  private fun sampleBaselineLayers(): List<CodeReviewBaselineLayer> = listOf(
    CodeReviewBaselineLayer(
      platform = "kmp",
      skill = "bill-kmp-code-review",
      scope = CodeReviewCompositionScope.SameReviewScope,
      required = true,
      mode = CodeReviewCompositionMode.KmpBaseline,
    ),
    CodeReviewBaselineLayer(
      platform = "java",
      skill = "bill-java-code-review",
      scope = CodeReviewCompositionScope.SameReviewScope,
      required = false,
      mode = CodeReviewCompositionMode.KmpBaseline,
    ),
  )

  private fun expectedRenderingWithBaselineLayers(): String = listOf(
    "platform: \"java\"",
    "contract_version: \"1.1\"",
    "display_name: \"Java\"",
    "",
    "routing_signals:",
    "  strong:",
    "    - \"pom.xml\"",
    "    - \"build.gradle\"",
    "  tie_breakers: []",
    "",
    "declared_code_review_areas: []",
    "",
    "declared_files:",
    "  baseline: \"code-review/bill-java-code-review/content.md\"",
    "  areas: {}",
    "area_metadata: {}",
    "",
    "declared_quality_check_file: \"quality-check/bill-java-code-check/content.md\"",
    "",
    "code_review_composition:",
    "  baseline_layers:",
    "    - platform: \"kmp\"",
    "      skill: \"bill-kmp-code-review\"",
    "      scope: \"same-review-scope\"",
    "      required: true",
    "      mode: \"kmp-baseline\"",
    "    - platform: \"java\"",
    "      skill: \"bill-java-code-review\"",
    "      scope: \"same-review-scope\"",
    "      required: false",
    "      mode: \"kmp-baseline\"",
  ).joinToString("\n") + "\n"
}

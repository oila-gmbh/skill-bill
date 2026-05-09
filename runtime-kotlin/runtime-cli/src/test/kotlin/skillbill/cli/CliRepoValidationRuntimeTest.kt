package skillbill.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliRepoValidationRuntimeTest {
  @Test
  fun `validate-release-ref emits json and github output`() {
    val tempDir = Files.createTempDirectory("skillbill-release-ref")
    val output = tempDir.resolve("github-output.txt")

    val result = CliRuntime.run(
      listOf(
        "validate-release-ref",
        "refs/tags/v1.2.3-rc.1",
        "--github-output",
        output.toString(),
        "--format",
        "json",
      ),
      CliRuntimeContext(),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "\"tag\": \"v1.2.3-rc.1\"")
    assertContains(result.stdout, "\"version\": \"1.2.3-rc.1\"")
    assertContains(result.stdout, "\"prerelease\": true")
    val githubOutput = Files.readString(output)
    assertContains(githubOutput, "tag=v1.2.3-rc.1")
    assertContains(githubOutput, "version=1.2.3-rc.1")
    assertContains(githubOutput, "prerelease=true")
  }

  @Test
  fun `validate-agent-configs command returns failure payload for empty repo`() {
    val repoRoot = Files.createTempDirectory("skillbill-empty-validation")

    val result = CliRuntime.run(
      listOf(
        "validate-agent-configs",
        "--repo-root",
        repoRoot.toString(),
        "--format",
        "json",
      ),
      CliRuntimeContext(),
    )

    assertEquals(1, result.exitCode)
    assertContains(result.stdout, "\"status\": \"failed\"")
    assertTrue(result.stdout.contains("skills/ directory is missing"))
  }

  @Test
  fun `validate-agent-configs command emits generated artifact guard issues`() {
    val repoRoot = Files.createTempDirectory("skillbill-cli-generated-guard")
    val skillDir = repoRoot.resolve("skills/bill-new-generated")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: bill-new-generated
      description: New generated wrapper fixture.
      ---

      # New Generated Fixture
      """.trimIndent() + "\n",
    )
    Files.writeString(skillDir.resolve("SKILL.md"), "generated wrapper\n")

    val result = CliRuntime.run(
      listOf(
        "validate-agent-configs",
        "--repo-root",
        repoRoot.toString(),
        "--format",
        "json",
      ),
      CliRuntimeContext(),
    )

    assertEquals(1, result.exitCode)
    assertContains(result.stdout, "\"status\": \"failed\"")
    assertTrue(result.stdout.contains("committed governed SKILL.md output is not allowed"), result.stdout)
  }

  @Test
  fun `validate-agent-configs command emits native agent composition failures`() {
    val repoRoot = Files.createTempDirectory("skillbill-cli-native-composition")
    writeMalformedComposedNativeAgentFixture(repoRoot)

    val result = CliRuntime.run(
      listOf(
        "validate-agent-configs",
        "--repo-root",
        repoRoot.toString(),
        "--format",
        "json",
      ),
      CliRuntimeContext(),
    )

    assertEquals(1, result.exitCode)
    assertContains(result.stdout, "\"status\": \"failed\"")
    assertTrue(
      result.stdout.contains("unsupported native agent compose directive 'local-file'"),
      result.stdout,
    )
  }

  private fun writeMalformedComposedNativeAgentFixture(repoRoot: Path) {
    val packRoot = repoRoot.resolve("platform-packs/fixture")
    Files.createDirectories(packRoot)
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      platform: fixture
      contract_version: "1.1"
      routing_signals:
        strong:
          - ".fixture"
        tie_breakers: []
      declared_code_review_areas:
        - architecture
      declared_files:
        baseline: code-review/bill-fixture-code-review/content.md
        areas:
          architecture: code-review/bill-fixture-code-review-architecture/content.md
      area_metadata:
        architecture:
          focus: "architecture review"
      """.trimIndent() + "\n",
    )
    writeContent(packRoot.resolve("code-review/bill-fixture-code-review/content.md"), "bill-fixture-code-review")
    writeContent(
      packRoot.resolve("code-review/bill-fixture-code-review-architecture/content.md"),
      "bill-fixture-code-review-architecture",
    )
    val nativeAgentDir = packRoot.resolve("code-review/bill-fixture-code-review/native-agents")
    Files.createDirectories(nativeAgentDir)
    Files.writeString(
      nativeAgentDir.resolve("bill-fixture-code-review-architecture.md"),
      """
      ---
      name: bill-fixture-code-review-architecture
      description: Architecture worker.
      compose: local-file
      ---
      """.trimIndent() + "\n",
    )
  }

  private fun writeContent(path: Path, name: String) {
    Files.createDirectories(path.parent)
    Files.writeString(
      path,
      """
      ---
      name: $name
      description: Fixture content.
      ---

      # Fixture

      Use this governed content.
      """.trimIndent() + "\n",
    )
  }
}

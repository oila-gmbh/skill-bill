package skillbill.cli

import java.nio.file.Files
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
}

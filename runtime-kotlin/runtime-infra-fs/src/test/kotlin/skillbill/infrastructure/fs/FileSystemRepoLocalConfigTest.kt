package skillbill.infrastructure.fs

import skillbill.config.model.ExecutionTier
import skillbill.config.model.PhaseModelDirective
import skillbill.config.model.RepoLocalConfig
import skillbill.config.model.SpecType
import skillbill.error.MalformedRepoLocalConfigError
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileSystemRepoLocalConfigTest {
  private val adapter = FileSystemRepoLocalConfig()

  @Test
  fun `reads typed values from a valid config file`() {
    val repoRoot = writeConfig(
      """
      spec_type: linear
      code_review_parallel_agent: claude
      """.trimIndent(),
    )

    val config = adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config

    assertEquals(SpecType.LINEAR, config.specType)
    assertEquals("claude", config.codeReviewParallelAgent)
  }

  @Test
  fun `missing config file yields built-in defaults with no error`() {
    val repoRoot = Files.createTempDirectory("skillbill-repo-local-config-missing")

    val config = adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config

    assertEquals(RepoLocalConfig.defaults(), config)
    assertEquals(SpecType.LOCAL, config.specType)
    assertEquals(RepoLocalConfig.NO_PARALLEL_AGENT, config.codeReviewParallelAgent)
  }

  @Test
  fun `absent known keys fall back to built-in defaults`() {
    val repoRoot = writeConfig("spec_type: linear")

    val config = adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config

    assertEquals(SpecType.LINEAR, config.specType)
    assertEquals(RepoLocalConfig.NO_PARALLEL_AGENT, config.codeReviewParallelAgent)
  }

  @Test
  fun `malformed yaml loud-fails naming the file at document root`() {
    val repoRoot = writeConfig("spec_type: [unterminated")

    val error = assertFailsWith<MalformedRepoLocalConfigError> {
      adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
    }

    assertContains(error.message.orEmpty(), repoRoot.fileName.toString())
    assertEquals("", error.key)
    assertContains(error.message.orEmpty(), "<root>")
  }

  @Test
  fun `non-scalar value for a known key loud-fails naming the key`() {
    val repoRoot = writeConfig(
      """
      spec_type:
        - local
        - linear
      """.trimIndent(),
    )

    val error = assertFailsWith<MalformedRepoLocalConfigError> {
      adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
    }

    assertEquals("spec_type", error.key)
    assertContains(error.message.orEmpty(), "spec_type")
  }

  @Test
  fun `invalid value for a known key loud-fails naming key and value`() {
    val repoRoot = writeConfig("spec_type: nonsense")

    val error = assertFailsWith<MalformedRepoLocalConfigError> {
      adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
    }

    assertEquals("spec_type", error.key)
    assertEquals("nonsense", error.value)
    assertContains(error.message.orEmpty(), "spec_type")
    assertContains(error.message.orEmpty(), "nonsense")
  }

  @Test
  fun `invalid parallel agent value loud-fails`() {
    val repoRoot = writeConfig("code_review_parallel_agent: not-an-agent")

    val error = assertFailsWith<MalformedRepoLocalConfigError> {
      adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
    }

    assertEquals("code_review_parallel_agent", error.key)
    assertEquals("not-an-agent", error.value)
  }

  @Test
  fun `unknown future keys are tolerated without error and do not affect known values`() {
    val repoRoot = writeConfig(
      """
      spec_type: linear
      future_unrelated_key: some-value
      """.trimIndent(),
    )

    val config = adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config

    assertEquals(SpecType.LINEAR, config.specType)
    assertEquals(RepoLocalConfig.NO_PARALLEL_AGENT, config.codeReviewParallelAgent)
  }

  @Test
  fun `reads execution matrix beside the existing flat config keys`() {
    val repoRoot = writeConfig(
      """
      spec_type: linear
      execution_matrix:
        phase_tiers:
          plan: implementation
        agents:
          codex:
            implementation:
              model: gpt-terra
              effort: high
      """.trimIndent(),
    )

    val config = adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config
    val matrix = requireNotNull(config.executionMatrix)

    assertEquals(SpecType.LINEAR, config.specType)
    assertEquals(ExecutionTier.IMPLEMENTATION, matrix.tierOf("plan"))
    assertEquals(
      PhaseModelDirective("gpt-terra", "high"),
      matrix.directiveFor("codex", "plan"),
    )
  }

  @Test
  fun `malformed execution matrix names the dotted config key`() {
    val repoRoot = writeConfig(
      """
      execution_matrix:
        agents:
          claude:
            reasoning:
              effort: high
      """.trimIndent(),
    )

    val error = assertFailsWith<MalformedRepoLocalConfigError> {
      adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
    }

    assertEquals("execution_matrix.agents.claude.reasoning.model", error.key)
  }

  private fun writeConfig(content: String): Path {
    val repoRoot = Files.createTempDirectory("skillbill-repo-local-config")
    val configPath = repoRoot.resolve(".skill-bill").resolve("config.yaml")
    Files.createDirectories(configPath.parent)
    Files.writeString(configPath, content)
    return repoRoot
  }
}

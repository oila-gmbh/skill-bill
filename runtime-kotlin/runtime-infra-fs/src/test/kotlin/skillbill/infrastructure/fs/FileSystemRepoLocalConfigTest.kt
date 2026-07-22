package skillbill.infrastructure.fs

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
  fun `review context budget overrides merge over governed defaults`() {
    val repoRoot = writeConfig(
      """
      review_context_budget:
        max_parent_packet_bytes: 600000
        max_lane_launch_bytes: 60000
        max_assignment_expansions: 1
        provider_token_thresholds:
          total_tokens: 100000
      """.trimIndent(),
    )
    val budget = adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config.reviewContextBudget
    assertEquals(600_000, budget.maxParentPacketBytes)
    assertEquals(60_000, budget.maxLaneLaunchBytes)
    assertEquals(1, budget.maxAssignmentExpansions)
    assertEquals(40_000, budget.providerTokenThresholds.inputTokens)
    assertEquals(100_000, budget.providerTokenThresholds.totalTokens)
  }

  @Test
  fun `review context budget rejects unknown nested keys before launch`() {
    val repoRoot = writeConfig(
      """
      review_context_budget:
        silently_truncate: true
      """.trimIndent(),
    )
    val error = assertFailsWith<MalformedRepoLocalConfigError> {
      adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
    }
    assertEquals("review_context_budget.silently_truncate", error.key)
  }

  @Test
  fun `review context budget rejects inconsistent limits before launch`() {
    val repoRoot = writeConfig(
      """
      review_context_budget:
        max_lane_evidence_bytes: 10
        max_evidence_result_bytes: 11
      """.trimIndent(),
    )
    assertFailsWith<MalformedRepoLocalConfigError> {
      adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
    }
  }

  @Test
  fun `review context budget rejects explicit null fractional and narrowing values`() {
    listOf(
      "review_context_budget: null",
      "review_context_budget:\n  max_lane_launch_bytes: 1.5",
      "review_context_budget:\n  max_assignment_expansions: 2147483648",
      "review_context_budget:\n  provider_token_thresholds: null",
      "review_context_budget:\n  max_lane_launch_bytes: null",
    ).forEach { content ->
      val repoRoot = writeConfig(content)
      assertFailsWith<MalformedRepoLocalConfigError>(content) {
        adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
      }
    }
  }

  @Test
  fun `ignores execution matrix because it belongs to the machine config`() {
    val repoRoot = writeConfig(
      """
      spec_type: linear
      execution_matrix:
        invalid: repo-local matrices are ignored
      """.trimIndent(),
    )

    val config = adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config

    assertEquals(SpecType.LINEAR, config.specType)
    assertEquals(RepoLocalConfig.NO_PARALLEL_AGENT, config.codeReviewParallelAgent)
  }

  private fun writeConfig(content: String): Path {
    val repoRoot = Files.createTempDirectory("skillbill-repo-local-config")
    val configPath = repoRoot.resolve(".skill-bill").resolve("config.yaml")
    Files.createDirectories(configPath.parent)
    Files.writeString(configPath, content)
    return repoRoot
  }
}

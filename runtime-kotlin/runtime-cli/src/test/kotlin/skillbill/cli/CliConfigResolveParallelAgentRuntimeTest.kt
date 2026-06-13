package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliConfigResolveParallelAgentRuntimeTest {
  @Test
  fun `config resolve-parallel-agent is registered and shows help`() {
    val result = CliRuntime.run(listOf("config", "resolve-parallel-agent", "--help"), CliRuntimeContext())

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "--arg")
    assertContains(result.stdout, "--repo-root")
  }

  @Test
  fun `arg overrides config and resolves that agent`() {
    val repoRoot = repoRootWithConfig("code_review_parallel_agent: none")

    val result = CliRuntime.run(
      listOf("config", "resolve-parallel-agent", "--arg", "codex", "--repo-root", repoRoot.toString()),
      CliRuntimeContext(),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("codex", result.stdout.trim())
  }

  @Test
  fun `blank arg defers to config code_review_parallel_agent`() {
    val repoRoot = repoRootWithConfig("code_review_parallel_agent: codex")

    val result = CliRuntime.run(
      listOf("config", "resolve-parallel-agent", "--repo-root", repoRoot.toString()),
      CliRuntimeContext(),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("codex", result.stdout.trim())
  }

  @Test
  fun `default arg sentinel defers to config code_review_parallel_agent`() {
    val repoRoot = repoRootWithConfig("code_review_parallel_agent: claude")

    val result = CliRuntime.run(
      listOf("config", "resolve-parallel-agent", "--arg", "default", "--repo-root", repoRoot.toString()),
      CliRuntimeContext(),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("claude", result.stdout.trim())
  }

  @Test
  fun `no config file resolves to none`() {
    val repoRoot = Files.createTempDirectory("config-parallel-no-config")

    val result = CliRuntime.run(
      listOf("config", "resolve-parallel-agent", "--repo-root", repoRoot.toString()),
      CliRuntimeContext(),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("none", result.stdout.trim())
  }

  @Test
  fun `missing key resolves to none`() {
    val repoRoot = repoRootWithConfig("spec_type: linear")

    val result = CliRuntime.run(
      listOf("config", "resolve-parallel-agent", "--repo-root", repoRoot.toString()),
      CliRuntimeContext(),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("none", result.stdout.trim())
  }

  @Test
  fun `malformed config value exits non-zero with named error`() {
    val repoRoot = repoRootWithConfig("code_review_parallel_agent: nonsense")

    val result = CliRuntime.run(
      listOf("config", "resolve-parallel-agent", "--repo-root", repoRoot.toString()),
      CliRuntimeContext(),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "is malformed")
    assertContains(result.stdout, "code_review_parallel_agent")
  }

  @Test
  fun `unrecognized arg exits non-zero`() {
    val repoRoot = Files.createTempDirectory("config-parallel-bad-arg")

    val result = CliRuntime.run(
      listOf("config", "resolve-parallel-agent", "--arg", "bogus", "--repo-root", repoRoot.toString()),
      CliRuntimeContext(),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "Unrecognized parallel agent value")
  }
}

private fun repoRootWithConfig(configBody: String): Path {
  val repoRoot = Files.createTempDirectory("config-resolve-parallel-agent")
  val configDir = Files.createDirectories(repoRoot.resolve(".skill-bill"))
  Files.writeString(configDir.resolve("config.yaml"), configBody)
  return repoRoot
}

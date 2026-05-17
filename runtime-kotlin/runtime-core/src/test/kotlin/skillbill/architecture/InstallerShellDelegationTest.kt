package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class InstallerShellDelegationTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }

  @Test
  fun `installer delegates install application to durable installed runtime`() {
    val installScript = Files.readString(runtimeRoot.parent.resolve("install.sh"))
    assertContains(installScript, "RUNTIME_INSTALL_ROOT")
    assertContains(installScript, "RUNTIME_MCP_BUILD_BIN")
    assertContains(installScript, "RUNTIME_MCP_BIN=\"\$RUNTIME_MCP_INSTALL_DIR/bin/runtime-mcp\"")
    assertContains(installScript, "RUNTIME_INSTALL_ARGS=(")
    assertContains(installScript, "install")
    assertContains(installScript, "apply")
    assertContains(installScript, "--agent-mode \"\$AGENT_SELECTION_MODE\"")
    assertContains(installScript, "--platform-mode \"\$PLATFORM_SELECTION_MODE\"")
    assertContains(installScript, "--telemetry \"\$TELEMETRY_LEVEL\"")
    assertContains(installScript, "--mcp \"\$MCP_REGISTRATION\"")
    assertContains(installScript, "--runtime-mcp-bin \"\$RUNTIME_MCP_BIN\"")
    assertFalse(installScript.contains("install register-mcp"))
    assertFalse(installScript.contains("install link-skill"))
    assertFalse(installScript.contains("install link-codex-agents"))
    assertFalse(installScript.contains("install link-claude-agents"))
    assertFalse(installScript.contains("install link-opencode-agents"))
    assertFalse(installScript.contains("install link-junie-agents"))
    assertFalse(installScript.contains("telemetry set-level"))
  }

  @Test
  fun `installer shell builds base-only all-agent runtime apply argv`() {
    val run = runInstallerShell(input = "1\nall\nbase only\noff\nskip\n")

    assertEquals(
      expectedApplyArgs(
        ExpectedApply(run, agentMode = "manual", platformMode = "none", telemetry = "off", mcp = "skip"),
      ) + listOf(
        "--agent",
        "copilot",
        "--agent-target",
        "copilot=${run.home.resolve("agent-targets/copilot")}",
        "--agent",
        "claude",
        "--agent-target",
        "claude=${run.home.resolve("agent-targets/claude")}",
        "--agent",
        "codex",
        "--agent-target",
        "codex=${run.home.resolve("agent-targets/codex")}",
        "--agent",
        "opencode",
        "--agent-target",
        "opencode=${run.home.resolve("agent-targets/opencode")}",
        "--agent",
        "junie",
        "--agent-target",
        "junie=${run.home.resolve("agent-targets/junie")}",
      ),
      run.applyArgs,
    )
  }

  @Test
  fun `installer shell builds selected platform runtime apply argv`() {
    val run = runInstallerShell(input = "1\ncodex\nkotlin\nfull\nregister\n")

    assertEquals(
      expectedApplyArgs(
        ExpectedApply(run, agentMode = "manual", platformMode = "selected", telemetry = "full", mcp = "register"),
      ) + listOf(
        "--agent",
        "codex",
        "--agent-target",
        "codex=${run.home.resolve("agent-targets/codex")}",
        "--platform",
        "kotlin",
      ),
      run.applyArgs,
    )
  }

  @Test
  fun `installer shell builds detected all-platform runtime apply argv`() {
    val run = runInstallerShell(input = "detected\nall\nanonymous\nregister\n")

    assertEquals(
      expectedApplyArgs(
        ExpectedApply(run, agentMode = "detected", platformMode = "all", telemetry = "anonymous", mcp = "register"),
      ),
      run.applyArgs,
    )
  }

  private fun runInstallerShell(input: String): InstallerShellRun {
    val repoRoot = Files.createTempDirectory("skillbill-installer-shell-repo")
    val home = Files.createTempDirectory("skillbill-installer-shell-home")
    val binDir = Files.createTempDirectory("skillbill-installer-shell-bin")
    val logPath = Files.createTempFile("skillbill-installer-shell-runtime", ".log")
    Files.writeString(repoRoot.resolve("install.sh"), Files.readString(runtimeRoot.parent.resolve("install.sh")))
    repoRoot.resolve("install.sh").toFile().setExecutable(true)
    Files.createDirectories(repoRoot.resolve("skills"))
    seedInstallerPlatformPack(repoRoot, "kmp")
    seedInstallerPlatformPack(repoRoot, "kotlin")
    seedInstallerRuntime(repoRoot)

    val process = ProcessBuilder("bash", repoRoot.resolve("install.sh").toString())
      .directory(repoRoot.toFile())
      .redirectErrorStream(true)
      .apply {
        environment()["HOME"] = home.toString()
        environment()["SKILL_BILL_BIN_DIR"] = binDir.toString()
        environment()["SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD"] = "1"
        environment()["SKILL_BILL_TEST_RUNTIME_LOG"] = logPath.toString()
      }
      .start()
    process.outputStream.bufferedWriter().use { writer -> writer.write(input) }
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    assertEquals(0, exitCode, output)
    val applyCalls = parseRuntimeCalls(logPath).filter { args ->
      args.drop(2).take(2) == listOf("install", "apply")
    }
    assertEquals(1, applyCalls.size, "installer must invoke runtime apply exactly once. Output:\n$output")
    return InstallerShellRun(
      repoRoot = repoRoot,
      home = home,
      binDir = binDir,
      applyArgs = applyCalls.single(),
    )
  }

  private fun expectedApplyArgs(expected: ExpectedApply): List<String> = listOf(
    "--home",
    expected.run.home.toString(),
    "install",
    "apply",
    "--repo-root",
    expected.run.repoRoot.toString(),
    "--skills",
    expected.run.repoRoot.resolve("skills").toString(),
    "--platform-packs",
    expected.run.repoRoot.resolve("platform-packs").toString(),
    "--agent-mode",
    expected.agentMode,
    "--platform-mode",
    expected.platformMode,
    "--telemetry",
    expected.telemetry,
    "--mcp",
    expected.mcp,
    "--replace-existing-skill-bill-links",
    "--runtime-install-root",
    expected.run.home.resolve(".skill-bill/runtime").toString(),
    "--runtime-cli-build-dir",
    expected.run.repoRoot.resolve("runtime-kotlin/runtime-cli/build/install/runtime-cli").toString(),
    "--runtime-mcp-build-dir",
    expected.run.repoRoot.resolve("runtime-kotlin/runtime-mcp/build/install/runtime-mcp").toString(),
    "--runtime-cli-install-dir",
    expected.run.home.resolve(".skill-bill/runtime/runtime-cli").toString(),
    "--runtime-mcp-install-dir",
    expected.run.home.resolve(".skill-bill/runtime/runtime-mcp").toString(),
    "--runtime-launcher-bin-dir",
    expected.run.binDir.toString(),
    "--runtime-mcp-bin",
    expected.run.home.resolve(".skill-bill/runtime/runtime-mcp/bin/runtime-mcp").toString(),
  )

  private fun seedInstallerRuntime(repoRoot: Path) {
    val cliBin = repoRoot.resolve("runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli")
    val mcpBin = repoRoot.resolve("runtime-kotlin/runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp")
    Files.createDirectories(cliBin.parent)
    Files.createDirectories(mcpBin.parent)
    Files.writeString(
      cliBin,
      """
      |#!/usr/bin/env bash
      |set -euo pipefail
      |{
      |  echo CALL
      |  for arg in "${'$'}@"; do
      |    printf 'ARG\t%s\n' "${'$'}arg"
      |  done
      |} >> "${'$'}{SKILL_BILL_TEST_RUNTIME_LOG:?}"
      |home=""
      |if [[ "${'$'}{1:-}" == "--home" ]]; then
      |  home="${'$'}2"
      |  shift 2
      |fi
      |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "agent-path" ]]; then
      |  printf '%s\n' "${'$'}home/agent-targets/${'$'}3"
      |  exit 0
      |fi
      |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "apply" ]]; then
      |  exit 0
      |fi
      |exit 2
      |
      """.trimMargin(),
    )
    Files.writeString(
      mcpBin,
      """
      |#!/usr/bin/env bash
      |exit 0
      |
      """.trimMargin(),
    )
    cliBin.toFile().setExecutable(true)
    mcpBin.toFile().setExecutable(true)
  }

  private fun seedInstallerPlatformPack(repoRoot: Path, slug: String) {
    val packRoot = repoRoot.resolve("platform-packs/$slug")
    Files.createDirectories(packRoot)
    Files.writeString(packRoot.resolve("platform.yaml"), "platform: \"$slug\"\n")
  }

  private fun parseRuntimeCalls(logPath: Path): List<List<String>> {
    val calls = mutableListOf<MutableList<String>>()
    Files.readAllLines(logPath).forEach { line ->
      if (line == "CALL") {
        calls.add(mutableListOf())
      } else if (line.startsWith("ARG\t")) {
        calls.last().add(line.removePrefix("ARG\t"))
      }
    }
    return calls
  }

  private data class ExpectedApply(
    val run: InstallerShellRun,
    val agentMode: String,
    val platformMode: String,
    val telemetry: String,
    val mcp: String,
  )

  private data class InstallerShellRun(
    val repoRoot: Path,
    val home: Path,
    val binDir: Path,
    val applyArgs: List<String>,
  )
}

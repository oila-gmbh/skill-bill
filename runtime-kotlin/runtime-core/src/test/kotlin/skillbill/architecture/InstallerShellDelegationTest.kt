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
    assertContains(installScript, "build_desktop_app_distribution")
    assertContains(installScript, ":runtime-desktop:prepareDesktopAppDistributable")
    assertContains(installScript, "install_desktop_app")
    assertContains(installScript, "desktop_host_os")
    assertContains(installScript, "printf '%s' \"/Applications\"")
    assertFalse(installScript.contains("prompt_for_mcp_registration"))
    assertFalse(installScript.contains("Enter MCP choice"))
    assertFalse(installScript.contains("install register-mcp"))
    assertFalse(installScript.contains("install link-skill"))
    assertFalse(installScript.contains("install link-codex-agents"))
    assertFalse(installScript.contains("install link-claude-agents"))
    assertFalse(installScript.contains("install link-opencode-agents"))
    assertFalse(installScript.contains("install link-junie-agents"))
    assertFalse(installScript.contains("telemetry set-level"))
  }

  @Test
  fun `uninstaller removes desktop app install and launcher`() {
    val run = runUninstallerShellWithDesktopInstall()

    assertEquals(0, run.exitCode, run.output)
    assertFalse(Files.exists(run.appTarget), "desktop app should be removed")
    assertFalse(Files.exists(run.launcherPath), "desktop launcher should be removed")
  }

  @Test
  fun `uninstaller removes desktop app before runtime dependent cleanup`() {
    val run = runUninstallerShellWithDesktopInstall(seedRuntime = false)

    assertFalse(run.exitCode == 0, "runtime-dependent cleanup should still fail without a runtime")
    assertFalse(Files.exists(run.appTarget), "desktop app should be removed")
    assertFalse(Files.exists(run.launcherPath), "desktop launcher should be removed")
  }

  @Test
  fun `installer shell builds base-only all-agent runtime apply argv`() {
    val run = runInstallerShell(input = "1\nall\nbase only\noff\nskip\n")

    assertEquals(
      expectedApplyArgs(
        ExpectedApply(run, agentMode = "manual", platformMode = "none", telemetry = "off", mcp = "register"),
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
    val run = runInstallerShell(input = "1\ncodex\nkotlin\nfull\nskip\n")

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
  fun `installer shell selected platform argv comes from discovered platform manifests`() {
    val run = runInstallerShell(input = "1\ncodex\npython\nfull\nskip\n")

    assertEquals(
      expectedApplyArgs(
        ExpectedApply(run, agentMode = "manual", platformMode = "selected", telemetry = "full", mcp = "register"),
      ) + listOf(
        "--agent",
        "codex",
        "--agent-target",
        "codex=${run.home.resolve("agent-targets/codex")}",
        "--platform",
        "python",
      ),
      run.applyArgs,
    )
  }

  @Test
  fun `installer shell builds detected all-platform runtime apply argv`() {
    val run = runInstallerShell(input = "detected\nall\nanonymous\nskip\n")

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
    seedInstallerPlatformPack(repoRoot, "python")
    seedInstallerRuntime(repoRoot)

    val process = ProcessBuilder("bash", repoRoot.resolve("install.sh").toString())
      .directory(repoRoot.toFile())
      .redirectErrorStream(true)
      .apply {
        environment()["HOME"] = home.toString()
        environment()["SKILL_BILL_BIN_DIR"] = binDir.toString()
        environment()["SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD"] = "1"
        environment()["SKILL_BILL_SKIP_PREINSTALL_UNINSTALL"] = "1"
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

  private fun runUninstallerShellWithDesktopInstall(seedRuntime: Boolean = true): UninstallerShellRun {
    val repoRoot = Files.createTempDirectory("skillbill-uninstaller-shell-repo")
    val home = Files.createTempDirectory("skillbill-uninstaller-shell-home")
    val binDir = Files.createTempDirectory("skillbill-uninstaller-shell-bin")
    val desktopRoot = Files.createTempDirectory("skillbill-uninstaller-shell-desktop")
    val logPath = Files.createTempFile("skillbill-uninstaller-shell-runtime", ".log")
    Files.writeString(repoRoot.resolve("uninstall.sh"), Files.readString(runtimeRoot.parent.resolve("uninstall.sh")))
    repoRoot.resolve("uninstall.sh").toFile().setExecutable(true)
    Files.createDirectories(repoRoot.resolve("skills/bill-test"))
    Files.writeString(repoRoot.resolve("skills/bill-test/content.md"), "# Test\n")
    seedInstallerPlatformPack(repoRoot, "kotlin")
    if (seedRuntime) {
      seedUninstallerRuntime(repoRoot)
    }

    val desktopInstall = seedDesktopInstall(desktopRoot, binDir)

    val process = ProcessBuilder(
      "bash",
      repoRoot.resolve("uninstall.sh").toString(),
      "--desktop-app-dir",
      desktopRoot.toString(),
    )
      .directory(repoRoot.toFile())
      .redirectErrorStream(true)
      .apply {
        environment()["HOME"] = home.toString()
        environment()["SKILL_BILL_BIN_DIR"] = binDir.toString()
        environment()["SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD"] = "1"
        environment()["SKILL_BILL_SKIP_PREINSTALL_UNINSTALL"] = "1"
        environment()["SKILL_BILL_TEST_RUNTIME_LOG"] = logPath.toString()
      }
      .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    return UninstallerShellRun(
      appTarget = desktopInstall.appTarget,
      launcherPath = desktopInstall.launcherPath,
      exitCode = exitCode,
      output = output,
    )
  }

  private fun seedDesktopInstall(desktopRoot: Path, binDir: Path): DesktopInstallFixture {
    val os = currentDesktopOs()
    val appTarget = when (os) {
      "macos" -> desktopRoot.resolve("SkillBill.app")
      else -> desktopRoot.resolve("SkillBill")
    }
    val executable = when (os) {
      "macos" -> appTarget.resolve("Contents/MacOS/SkillBill")
      "windows" -> appTarget.resolve("bin/SkillBill.bat")
      else -> appTarget.resolve("bin/SkillBill")
    }
    Files.createDirectories(executable.parent)
    Files.writeString(executable, "")
    executable.toFile().setExecutable(true)
    return DesktopInstallFixture(appTarget, seedDesktopLauncher(os, binDir, executable))
  }

  private fun seedDesktopLauncher(os: String, binDir: Path, executable: Path): Path = when (os) {
    "windows" -> {
      val launcher = binDir.resolve("skillbill-desktop.cmd")
      Files.writeString(launcher, "@echo off\ncall \"${executable}\" %*\n")
      launcher
    }
    else -> {
      val launcher = binDir.resolve("skillbill-desktop")
      Files.createSymbolicLink(launcher, executable)
      launcher
    }
  }

  private fun seedUninstallerRuntime(repoRoot: Path) {
    val cliBin = repoRoot.resolve("runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli")
    Files.createDirectories(cliBin.parent)
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
      |if [[ "${'$'}{1:-}" == "--home" ]]; then
      |  shift 2
      |fi
      |case "${'$'}{1:-} ${'$'}{2:-}" in
      |  "install cleanup-agent-target"|"install unlink-codex-agents"|"install unlink-opencode-agents"|"install unlink-junie-agents"|"install unregister-mcp")
      |    exit 0
      |    ;;
      |esac
      |exit 2
      |
      """.trimMargin(),
    )
    cliBin.toFile().setExecutable(true)
  }

  private fun currentDesktopOs(): String {
    val osName = System.getProperty("os.name").lowercase()
    return when {
      osName.contains("mac") -> "macos"
      osName.contains("win") -> "windows"
      osName.contains("linux") -> "linux"
      else -> "unknown"
    }
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

  private data class UninstallerShellRun(
    val appTarget: Path,
    val launcherPath: Path,
    val exitCode: Int,
    val output: String,
  )

  private data class DesktopInstallFixture(
    val appTarget: Path,
    val launcherPath: Path,
  )
}

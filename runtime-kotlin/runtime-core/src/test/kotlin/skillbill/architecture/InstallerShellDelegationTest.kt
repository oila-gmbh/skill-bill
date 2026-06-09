package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    assertContains(installScript, "--reuse-last-selection")
    assertContains(installScript, "install replay-last-selection")
    assertContains(installScript, "SKILL_BILL_RUNTIME_EXECUTABLE=\"\$RUNTIME_CLI_BIN\"")
    // The Gradle desktop build is now gated behind --from-source: the helper and the
    // Gradle task must still exist (from-source coverage), but only run when
    // INSTALL_SOURCE=source. The prebuilt path fetches + extracts instead.
    assertContains(installScript, "build_desktop_app_distribution")
    assertContains(installScript, ":runtime-desktop:prepareDesktopAppDistributable")
    assertContains(installScript, "if [[ \"\$INSTALL_SOURCE\" != \"source\" ]]; then")
    assertContains(installScript, "fetch_and_extract_desktop_app")
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
    assertFalse(installScript.contains("firstRun."))
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

  @Test
  fun `prebuilt default installs runtime from staged release without gradle`() {
    val run = runPrebuiltInstaller(releaseValid = true)

    assertEquals(0, run.exitCode, run.output)
    // No Gradle invocation on the prebuilt path.
    assertFalse(run.output.contains("gradlew"), "prebuilt install must not call Gradle. Output:\n${run.output}")
    assertFalse(
      run.runtimeLog.contains("installDist"),
      "prebuilt install must not run a Gradle installDist build",
    )
    assertContains(run.output, "verified checksum:")
    assertContains(run.output, "Kotlin runtime installed from prebuilt release")
    // The runtime binaries landed under the durable per-user runtime root.
    assertTrue(
      Files.isExecutable(run.home.resolve(".skill-bill/runtime/runtime-cli/bin/runtime-cli")),
      "runtime-cli should be installed from the staged release",
    )
    assertTrue(
      Files.isExecutable(run.home.resolve(".skill-bill/runtime/runtime-mcp/bin/runtime-mcp")),
      "runtime-mcp should be installed from the staged release",
    )
  }

  @Test
  fun `prebuilt install fails loudly on checksum mismatch with no partial install`() {
    val run = runPrebuiltInstaller(releaseValid = false)

    assertFalse(run.exitCode == 0, "checksum mismatch must fail non-zero. Output:\n${run.output}")
    assertContains(run.output, "Checksum mismatch")
    // No partial runtime install survives the failure.
    assertFalse(
      Files.exists(run.home.resolve(".skill-bill/runtime/runtime-cli")),
      "no partial runtime-cli should be installed on checksum failure",
    )
  }

  @Test
  fun `from-source keeps gradle skip-build install behavior`() {
    // --from-source with the SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD escape hatch
    // must still route through the durable copy path, never the prebuilt fetch.
    val run = runInstallerShell(input = "1\ncopilot\nbase only\noff\nskip\n", fromSource = true)

    assertContains(run.output, "Installing runtime from source (--from-source)")
    assertFalse(run.output.contains("verified checksum:"), "from-source must not verify release checksums")
  }

  @Test
  fun `prebuilt auto-falls back to source when host token has no matching asset`() {
    // A staged release dir with NO assets for this host forces the explicit
    // auto-fallback message and the source build path.
    val run = runPrebuiltInstaller(releaseValid = true, options = PrebuiltOptions(omitRuntimeAssets = true))

    assertEquals(0, run.exitCode, run.output)
    assertContains(run.output, "falling back to a from-source Gradle build")
  }

  @Test
  fun `display gating defaults headless to no and explicit flag forces install`() {
    // Headless (no DISPLAY/WAYLAND_DISPLAY), non-interactive → desktop default no.
    val headless = runPrebuiltInstaller(releaseValid = true)
    assertEquals(0, headless.exitCode, headless.output)
    assertContains(headless.output, "Desktop app:    no")

    // Explicit --with-desktop-app forces yes even non-interactive.
    val forced = runPrebuiltInstaller(releaseValid = true, extraArgs = listOf("--with-desktop-app"))
    assertEquals(0, forced.exitCode, forced.output)
    assertContains(forced.output, "Desktop app:    yes")
  }

  @Test
  fun `interactive headless empty input defaults desktop app to no`() {
    // A TTY-attached but headless session (no DISPLAY/WAYLAND_DISPLAY): pressing
    // Enter at the desktop prompt must resolve to the gated default (skip), not the
    // old hardcoded install. Driven under a real PTY via `script`.
    val run = runPrebuiltInstaller(
      releaseValid = true,
      options = PrebuiltOptions(desktopInput = "", interactiveTty = true),
    )
    assertEquals(0, run.exitCode, run.output)
    assertContains(run.output, "Desktop app:    no")
  }

  @Test
  fun `prebuilt desktop extract installs app launcher and prints unsigned hint`() {
    assumeDebHost()
    val run = runPrebuiltInstaller(releaseValid = true, extraArgs = listOf("--with-desktop-app"))

    assertEquals(0, run.exitCode, run.output)
    assertContains(run.output, "Desktop app installed")
    assertTrue(
      Files.exists(run.binDir.resolve("skillbill-desktop")),
      "skillbill-desktop launcher should be installed",
    )
    // Linux has no signing gate, so no unsigned hint is printed on the deb host.
    assertFalse(
      run.output.contains("unsigned for v1"),
      "Linux desktop install must not print a Gatekeeper/SmartScreen hint",
    )
  }

  @Test
  fun `install plan summary is printed before any mutation`() {
    // Do NOT set SKILL_BILL_SKIP_PREINSTALL_UNINSTALL: the plan must print before
    // the pre-install uninstall mutates anything.
    val run = runPrebuiltInstaller(releaseValid = true, options = PrebuiltOptions(skipPreinstallUninstall = false))

    assertEquals(0, run.exitCode, run.output)
    val planIndex = run.output.indexOf("What this installer will change")
    val cleanupIndex = run.output.indexOf("Pre-install cleanup")
    assertTrue(planIndex >= 0, "install plan must be printed. Output:\n${run.output}")
    assertTrue(cleanupIndex >= 0, "pre-install cleanup must run. Output:\n${run.output}")
    assertTrue(planIndex < cleanupIndex, "the plan must print before the first mutation")
    assertContains(run.output, "Reverse everything with:")
  }

  @Test
  fun `desktop-app-only installs only the desktop app and is idempotent`() {
    assumeDebHost()
    val first = runPrebuiltInstaller(releaseValid = true, extraArgs = listOf("--desktop-app-only"))
    assertEquals(0, first.exitCode, first.output)
    assertContains(first.output, "Desktop app installed")
    // No runtime apply on the desktop-only path.
    assertFalse(
      first.runtimeLog.contains("install\tapply") || first.runtimeLog.contains("apply"),
      "desktop-app-only must not run runtime apply. Log:\n${first.runtimeLog}",
    )
    assertTrue(
      Files.exists(first.binDir.resolve("skillbill-desktop")),
      "desktop launcher should be installed",
    )

    // Re-run against the same home/binDir: idempotent, still succeeds.
    val second = runPrebuiltInstaller(
      releaseValid = true,
      extraArgs = listOf("--desktop-app-only"),
      reuse = PrebuiltReuse(first.home, first.binDir, first.desktopDir),
    )
    assertEquals(0, second.exitCode, second.output)
    assertContains(second.output, "Desktop app installed")
  }

  @Test
  fun `uninstaller removes prebuilt desktop install`() {
    // The prebuilt desktop install reuses the same per-user locations and launcher
    // names, so the existing uninstaller covers it.
    val run = runUninstallerShellWithDesktopInstall()
    assertEquals(0, run.exitCode, run.output)
    assertFalse(Files.exists(run.appTarget), "desktop app should be removed")
    assertFalse(Files.exists(run.launcherPath), "desktop launcher should be removed")
  }

  private fun runInstallerShell(input: String): InstallerShellRun = runInstallerShell(input, fromSource = false)

  private fun runInstallerShell(input: String, fromSource: Boolean): InstallerShellRun {
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

    val command = mutableListOf("bash", repoRoot.resolve("install.sh").toString())
    if (fromSource) {
      command.add("--from-source")
    }
    val process = ProcessBuilder(command)
      .directory(repoRoot.toFile())
      .redirectErrorStream(true)
      .apply {
        environment()["HOME"] = home.toString()
        environment()["SKILL_BILL_BIN_DIR"] = binDir.toString()
        environment()["SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD"] = "1"
        environment()["SKILL_BILL_SKIP_PREINSTALL_UNINSTALL"] = "1"
        environment()["SKILL_BILL_TEST_RUNTIME_LOG"] = logPath.toString()
        environment().remove("SKILL_BILL_GOAL_CONTINUATION")
        // Headless + non-interactive: scrub inherited desktop-session signals so the
        // desktop-app prompt deterministically defaults to "no" (no Gradle desktop
        // build is needed for these runtime-apply argv assertions).
        environment().remove("DISPLAY")
        environment().remove("WAYLAND_DISPLAY")
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
      output = output,
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
      |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "claude-roots" ]]; then
      |  printf '%s\n' "${'$'}home/.claude"
      |  exit 0
      |fi
      |# Pre-install uninstall (AC6 path) drives the same CLI for cleanup commands;
      |# answer them with empty output + success so the clean slate reset succeeds.
      |case "${'$'}{1:-} ${'$'}{2:-}" in
      |  "install cleanup-agent-target"|"install unlink-codex-agents"|"install unlink-claude-agents"|"install unlink-opencode-agents"|"install unlink-junie-agents"|"install unregister-mcp")
      |    exit 0
      |    ;;
      |esac
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
        environment().remove("SKILL_BILL_GOAL_CONTINUATION")
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
      |home="${'$'}{HOME:-}"
      |if [[ "${'$'}{1:-}" == "--home" ]]; then
      |  home="${'$'}2"
      |  shift 2
      |fi
      |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "claude-roots" ]]; then
      |  printf '%s\n' "${'$'}home/.claude"
      |  exit 0
      |fi
      |case "${'$'}{1:-} ${'$'}{2:-}" in
      |  "install cleanup-agent-target"|"install unlink-codex-agents"|"install unlink-claude-agents"|"install unlink-opencode-agents"|"install unlink-junie-agents"|"install unregister-mcp")
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

  // Drive install.sh over a STAGED RELEASE directory (SKILL_BILL_RELEASE_DIR) with
  // no network and no Gradle skip-build hatch, so the real prebuilt fetch +
  // checksum-verify + unpack path runs end-to-end. The staged dir contains fake
  // runtime-cli/runtime-mcp .zip images (each with a real bin/<base>), a desktop
  // installer for this host, and matching `<hex>␣␣<name>` .sha256 siblings.
  private fun runPrebuiltInstaller(
    releaseValid: Boolean,
    extraArgs: List<String> = emptyList(),
    reuse: PrebuiltReuse? = null,
    options: PrebuiltOptions = PrebuiltOptions(),
  ): PrebuiltInstallerRun {
    // The staged-release fixtures require bsdtar (runtime image zips) and, on a
    // linux-x64 host, ar (the real .deb). Skip cleanly when the tooling is absent
    // so `./gradlew check` does not ERROR for contributors without libarchive-tools.
    PrebuiltReleaseStager.assumeReleaseStagingTools()
    if (options.interactiveTty) {
      // The interactive desktop default is only reachable with a real TTY on stdin.
      // `script` allocates a PTY; gate the test on its presence so it skips cleanly
      // rather than failing on hosts without util-linux.
      org.junit.jupiter.api.Assumptions.assumeTrue(
        PrebuiltReleaseStager.toolOnPath("script"),
        "interactive-TTY install test requires `script` (util-linux) on PATH",
      )
    }
    val desktopOnly = extraArgs.contains("--desktop-app-only")
    val repoRoot = Files.createTempDirectory("skillbill-prebuilt-repo")
    val home = reuse?.home ?: Files.createTempDirectory("skillbill-prebuilt-home")
    val binDir = reuse?.binDir ?: Files.createTempDirectory("skillbill-prebuilt-bin")
    val desktopDir = reuse?.desktopDir ?: Files.createTempDirectory("skillbill-prebuilt-desktop")
    val releaseDir = Files.createTempDirectory("skillbill-prebuilt-release")
    val logPath = Files.createTempFile("skillbill-prebuilt-runtime", ".log")
    seedPrebuiltRepo(repoRoot)
    stageRelease(releaseDir, releaseValid, options.omitRuntimeAssets)

    val command = PrebuiltReleaseStager.buildPrebuiltCommand(repoRoot, extraArgs, desktopDir, options.interactiveTty)
    val builder = ProcessBuilder(command)
      .directory(repoRoot.toFile())
      .redirectErrorStream(true)
    builder.environment()["HOME"] = home.toString()
    builder.environment()["SKILL_BILL_BIN_DIR"] = binDir.toString()
    builder.environment()["SKILL_BILL_RELEASE_DIR"] = releaseDir.toString()
    builder.environment()["SKILL_BILL_TEST_RUNTIME_LOG"] = logPath.toString()
    builder.environment().remove("SKILL_BILL_GOAL_CONTINUATION")
    if (options.skipPreinstallUninstall) {
      builder.environment()["SKILL_BILL_SKIP_PREINSTALL_UNINSTALL"] = "1"
    }
    // Headless by default: drop any inherited desktop-session signals.
    builder.environment().remove("DISPLAY")
    builder.environment().remove("WAYLAND_DISPLAY")

    val process = builder.start()
    // The full flow prompts for agent/platform/telemetry/desktop; feed a stable
    // base-only single-agent selection. The desktop-only path reads no input.
    val input = if (desktopOnly) "" else "1\ncopilot\nbase only\noff\n${options.desktopInput}\n"
    process.outputStream.bufferedWriter().use { writer -> writer.write(input) }
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    val runtimeLog = if (Files.exists(logPath)) Files.readString(logPath) else ""

    return PrebuiltInstallerRun(
      home = home,
      binDir = binDir,
      desktopDir = desktopDir,
      exitCode = exitCode,
      output = output,
      runtimeLog = runtimeLog,
    )
  }

  private fun seedPrebuiltRepo(repoRoot: Path) {
    Files.writeString(repoRoot.resolve("install.sh"), Files.readString(runtimeRoot.parent.resolve("install.sh")))
    repoRoot.resolve("install.sh").toFile().setExecutable(true)
    Files.writeString(repoRoot.resolve("uninstall.sh"), Files.readString(runtimeRoot.parent.resolve("uninstall.sh")))
    repoRoot.resolve("uninstall.sh").toFile().setExecutable(true)
    Files.createDirectories(repoRoot.resolve("skills"))
    // The full flow + pre-install uninstall expect the build-dir runtime + a Gradle
    // wrapper (the latter only used on the auto-fallback path). Seed both plus the
    // desktop icon install_linux_desktop_entry copies.
    seedInstallerRuntime(repoRoot)
    seedFakeGradlew(repoRoot)
    seedDesktopIcon(repoRoot)
  }

  // A no-op Gradle wrapper. The prebuilt path never calls it; the auto-fallback
  // path does, and seedInstallerRuntime already left the build-dir runtime in
  // place for build_kotlin_runtime_distributions to copy.
  private fun seedFakeGradlew(repoRoot: Path) {
    val gradlew = repoRoot.resolve("runtime-kotlin/gradlew")
    Files.createDirectories(gradlew.parent)
    Files.writeString(gradlew, "#!/usr/bin/env bash\nexit 0\n")
    gradlew.toFile().setExecutable(true)
  }

  private fun seedDesktopIcon(repoRoot: Path) {
    val icon = repoRoot.resolve("runtime-kotlin/runtime-desktop/icons/icon.png")
    Files.createDirectories(icon.parent)
    Files.write(icon, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
  }

  // Stage a fake GitHub release directory for SKILL_BILL_RELEASE_DIR. Runtime images
  // are real .zip files containing bin/<base>; the desktop installer is a real .deb
  // (ar + data.tar) on Linux hosts so ar/tar extraction is genuinely exercised. When
  // releaseValid is false, the runtime-cli checksum is corrupted to exercise AC2.
  private fun stageRelease(releaseDir: Path, releaseValid: Boolean, omitRuntimeAssets: Boolean) {
    PrebuiltReleaseStager.stage(releaseDir, releaseValid, omitRuntimeAssets)
  }

  private fun hostTokenForTests(): String = PrebuiltReleaseStager.hostToken()

  private fun assumeDebHost() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
      hostTokenForTests() == "linux-x64",
      "desktop-extract assertions require a linux-x64 host with ar/tar",
    )
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
    val output: String,
  )

  private data class PrebuiltReuse(
    val home: Path,
    val binDir: Path,
    val desktopDir: Path,
  )

  // Optional knobs for the prebuilt flow. `desktopInput` is the answer fed to the
  // desktop-app prompt (e.g. "skip", "" for Enter); `interactiveTty` runs the
  // installer under a real PTY so the interactive (non-piped) prompt branch executes.
  private data class PrebuiltOptions(
    val omitRuntimeAssets: Boolean = false,
    val skipPreinstallUninstall: Boolean = true,
    val desktopInput: String = "skip",
    val interactiveTty: Boolean = false,
  )

  private data class PrebuiltInstallerRun(
    val home: Path,
    val binDir: Path,
    val desktopDir: Path,
    val exitCode: Int,
    val output: String,
    val runtimeLog: String,
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

// Builds a fake GitHub-release directory (for SKILL_BILL_RELEASE_DIR) carrying real
// runtime-cli/runtime-mcp image zips, a per-host desktop installer, and matching
// `<hex>␣␣<name>` .sha256 siblings. Kept as a standalone helper to keep the test
// class focused (and under detekt's LargeClass threshold).
private object PrebuiltReleaseStager {
  private const val VERSION = "9.9.9"

  fun hostToken(): String {
    val os = when {
      System.getProperty("os.name").lowercase().contains("mac") -> "macos"
      System.getProperty("os.name").lowercase().contains("win") -> "windows"
      System.getProperty("os.name").lowercase().contains("linux") -> "linux"
      else -> "unknown"
    }
    val arch = when (val raw = System.getProperty("os.arch").lowercase()) {
      "aarch64", "arm64" -> "arm64"
      "x86_64", "amd64" -> "x64"
      else -> raw
    }
    return "$os-$arch"
  }

  // The staged release fixtures author runtime image zips with bsdtar and, on a
  // linux-x64 host, a real .deb with ar. Skip (not fail) the prebuilt-path tests
  // when that tooling is missing so `./gradlew check` stays green for contributors
  // without libarchive-tools (bsdtar) or binutils (ar).
  fun assumeReleaseStagingTools() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
      toolOnPath("bsdtar"),
      "staged-release runtime image zips require bsdtar on PATH",
    )
    if (hostToken() == "linux-x64") {
      org.junit.jupiter.api.Assumptions.assumeTrue(
        toolOnPath("ar"),
        "the staged-release .deb fixture requires ar on PATH",
      )
    }
  }

  fun toolOnPath(tool: String): Boolean = (System.getenv("PATH") ?: "")
    .split(java.io.File.pathSeparatorChar)
    .filter { it.isNotEmpty() }
    .any { dir -> Files.isExecutable(Path.of(dir).resolve(tool)) }

  fun buildPrebuiltCommand(
    repoRoot: Path,
    extraArgs: List<String>,
    desktopDir: Path,
    interactiveTty: Boolean,
  ): List<String> {
    val installArgs = mutableListOf(repoRoot.resolve("install.sh").toString())
    installArgs.addAll(extraArgs)
    if (extraArgs.none { it == "--desktop-app-dir" }) {
      installArgs.add("--desktop-app-dir")
      installArgs.add(desktopDir.toString())
    }
    if (!interactiveTty) {
      return listOf("bash") + installArgs
    }
    // `script -qec "<cmd>" /dev/null` runs the installer under a PTY so the installer
    // sees a TTY on stdin and takes the interactive desktop-prompt branch. `script`
    // still forwards our piped stdin into the PTY.
    val quoted = (listOf("bash") + installArgs).joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
    return listOf("script", "-qec", quoted, "/dev/null")
  }

  fun stage(releaseDir: Path, releaseValid: Boolean, omitRuntimeAssets: Boolean) {
    val token = hostToken()
    if (!omitRuntimeAssets) {
      val cliZip = releaseDir.resolve("runtime-cli-$VERSION-$token.zip")
      val mcpZip = releaseDir.resolve("runtime-mcp-$VERSION-$token.zip")
      writeRuntimeImageZip(cliZip, "runtime-cli")
      writeRuntimeImageZip(mcpZip, "runtime-mcp")
      writeChecksumSibling(cliZip, corrupt = !releaseValid)
      writeChecksumSibling(mcpZip, corrupt = false)
    }
    when (token) {
      "linux-x64" -> {
        val deb = releaseDir.resolve("SkillBill-$VERSION-$token.deb")
        writeDebDesktopInstaller(deb)
        writeChecksumSibling(deb, corrupt = false)
      }
      else -> {
        // Non-deb hosts: stage a placeholder so resolve_release_assets matches; the
        // desktop-extract assertions are gated behind assumeDebHost().
        val ext = when {
          token.startsWith("macos") -> "dmg"
          token.startsWith("windows") -> "msi"
          else -> "deb"
        }
        val installer = releaseDir.resolve("SkillBill-$VERSION-$token.$ext")
        Files.write(installer, byteArrayOf(0))
        writeChecksumSibling(installer, corrupt = false)
      }
    }
  }

  // Build the runtime image .zip carrying the installDist layout
  // <base>/bin/<base> + <base>/lib/<base>.jar that unpack_runtime_image expects.
  // Use bsdtar (present on macOS/Linux, also the .deb extraction fallback) so the
  // launcher's executable bit survives the zip → unzip round-trip and
  // locate_packaged_runtime_bin's `-x` check passes.
  private fun writeRuntimeImageZip(zipPath: Path, base: String) {
    val staging = Files.createTempDirectory("skillbill-image-$base")
    val binDir = staging.resolve("$base/bin")
    Files.createDirectories(binDir)
    val launcher = binDir.resolve(base)
    // The installed runtime-cli is exercised by the full flow (agent-path + apply),
    // so give it the same logging/dispatch stub the build-dir runtime uses. The mcp
    // launcher only needs to exist + be executable.
    Files.writeString(launcher, if (base == "runtime-cli") stagedCliStub else "#!/usr/bin/env bash\nexit 0\n")
    launcher.toFile().setExecutable(true)
    val lib = staging.resolve("$base/lib")
    Files.createDirectories(lib)
    Files.writeString(lib.resolve("$base.jar"), "stub")
    runOrThrow(staging, listOf("bsdtar", "-a", "-cf", zipPath.toString(), base))
  }

  // Build a minimal but real .deb: `ar` archive containing debian-binary,
  // control.tar.gz, and data.tar.gz; data.tar.gz carries the jpackage app layout
  // (opt/skillbill/SkillBill/bin/SkillBill + lib/). This exercises the real
  // ar + tar extraction path on Linux CI hosts.
  private fun writeDebDesktopInstaller(debPath: Path) {
    val staging = Files.createTempDirectory("skillbill-deb")
    val appRoot = staging.resolve("data/opt/skillbill/SkillBill")
    Files.createDirectories(appRoot.resolve("bin"))
    Files.createDirectories(appRoot.resolve("lib"))
    val appLauncher = appRoot.resolve("bin/SkillBill")
    Files.writeString(appLauncher, "#!/usr/bin/env bash\nexit 0\n")
    appLauncher.toFile().setExecutable(true)
    Files.writeString(appRoot.resolve("lib/app.jar"), "stub")

    val controlDir = staging.resolve("control")
    Files.createDirectories(controlDir)
    Files.writeString(controlDir.resolve("control"), "Package: skillbill\nVersion: 9.9.9\n")

    runOrThrow(staging, listOf("tar", "-czf", "data.tar.gz", "-C", "data", "."))
    runOrThrow(staging, listOf("tar", "-czf", "control.tar.gz", "-C", "control", "."))
    Files.writeString(staging.resolve("debian-binary"), "2.0\n")
    runOrThrow(staging, listOf("ar", "rc", debPath.toString(), "debian-binary", "control.tar.gz", "data.tar.gz"))
  }

  private fun writeChecksumSibling(asset: Path, corrupt: Boolean) {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val hexReal = digest.digest(Files.readAllBytes(asset)).joinToString("") { "%02x".format(it) }
    val hex = if (corrupt) "0".repeat(64) else hexReal
    Files.writeString(asset.resolveSibling("${asset.fileName}.sha256"), "$hex  ${asset.fileName}\n")
  }

  private fun runOrThrow(cwd: Path, command: List<String>) {
    val process = ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "command failed: ${command.joinToString(" ")}\n$out" }
  }

  private val stagedCliStub =
    """
    |#!/usr/bin/env bash
    |set -euo pipefail
    |if [[ -n "${'$'}{SKILL_BILL_TEST_RUNTIME_LOG:-}" ]]; then
    |  {
    |    echo CALL
    |    for arg in "${'$'}@"; do
    |      printf 'ARG\t%s\n' "${'$'}arg"
    |    done
    |  } >> "${'$'}SKILL_BILL_TEST_RUNTIME_LOG"
    |fi
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
    |exit 0
    |
    """.trimMargin()
}

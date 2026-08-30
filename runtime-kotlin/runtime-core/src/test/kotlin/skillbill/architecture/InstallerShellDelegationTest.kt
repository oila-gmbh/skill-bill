package skillbill.architecture

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallerShellDelegationTest {
  @Test
  fun `installer delegates install application to durable installed runtime`() {
    val installScript = Files.readString(installerShellRuntimeRoot.parent.resolve("install.sh"))
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
    assertContains(installScript, "exec \"\\\$runtime_cli\" update \"\\\${passthrough[@]+\\\${passthrough[@]}}\"")
    assertExternalAddonOverlayOrdering(installScript)
    assertFalse(installScript.contains("update_check_status"))
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
        "claude",
        "--agent",
        "codex",
        "--agent-target",
        "codex=${run.home.resolve("agent-targets/codex")}",
        "--agent",
        "junie",
        "--agent-target",
        "junie=${run.home.resolve("agent-targets/junie")}",
        "--agent",
        "cursor",
        "--agent-target",
        "cursor=${run.home.resolve("agent-targets/cursor")}",
      ),
      run.applyArgs,
    )
    assertCopyInPopulatedRealFiles(run)
  }

  @Test
  fun `installer copy-in materializes self-contained source so deleting the clone keeps skills resolving`() {



    val run = runInstallerShell(input = "1\nclaude\nbase only\noff\nskip\n")

    assertCopyInPopulatedRealFiles(run)


    Files.walk(run.repoRoot).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
    assertFalse(Files.exists(run.repoRoot), "clone should be deleted for the AC-3 check")
    val copiedSkill = run.home.resolve(".skill-bill/skills/bill-sample/content.md")
    assertTrue(
      Files.isRegularFile(copiedSkill),
      "AC-3: copied skill content.md must survive clone deletion at $copiedSkill",
    )
    assertTrue(
      Files.isRegularFile(run.home.resolve(".skill-bill/orchestration/review-orchestrator/PLAYBOOK.md")),
      "AC-3: copied orchestration playbook must survive clone deletion",
    )
    assertTrue(
      Files.isRegularFile(run.home.resolve(".skill-bill/agent-addons/review-helper/content.md")),
      "AC-3: copied agent add-on source must survive clone deletion",
    )
  }

  @Test
  fun `pre-install wipe preserves copied source reserved baseline and state dbs`() {




    val fixtures = seedStateDirForWipe()
    val run = runUninstaller(fixtures, preserveSource = true, goalContinuation = false)

    assertEquals(0, run.exitCode, run.output)

    assertTrue(Files.isRegularFile(fixtures.skillContent), "skills/ must be preserved under preserve-wipe")
    assertTrue(Files.isRegularFile(fixtures.packYaml), "platform-packs/ must be preserved under preserve-wipe")
    assertTrue(Files.isRegularFile(fixtures.orchestrationPlaybook), "orchestration/ must be preserved")
    assertTrue(Files.isRegularFile(fixtures.baselineManifest), "reserved baseline-manifest path must be preserved")

    assertFalse(Files.exists(fixtures.runtimeBin), "runtime/ must be cleared under preserve-wipe")
    assertFalse(Files.exists(fixtures.installedSkill), "installed-skills/ must be cleared under preserve-wipe")
    assertTrue(Files.isRegularFile(fixtures.stateDb), "*.db state DBs must be preserved under preserve-wipe")
  }

  @Test
  fun `pre-install wipe continues when native subagent unlink cleanup fails`() {
    val fixtures = seedStateDirForWipe(failingNativeUnlinkCommand = "install unlink-claude-agents")
    val run = runUninstaller(fixtures, preserveSource = true, goalContinuation = false)

    assertEquals(0, run.exitCode, run.output)
    assertContains(run.output, "Claude subagent cleanup failed; continuing uninstall so reinstall can recover.")
    assertFalse(Files.exists(fixtures.runtimeBin), "runtime/ must still be cleared after native cleanup failure")
    assertFalse(
      Files.exists(fixtures.installedSkill),
      "installed-skills/ must still be cleared after native cleanup failure",
    )
    assertTrue(Files.isRegularFile(fixtures.skillContent), "copied source must still be preserved")
  }

  @Test
  fun `goal continuation exit-64 guard preserves the entire state dir including the active goal db`() {




    val fixtures = seedStateDirForWipe()
    val run = runUninstaller(fixtures, preserveSource = true, goalContinuation = true)

    assertEquals(64, run.exitCode, run.output)
    assertContains(run.output, "Refusing to run uninstall.sh during skill-bill goal-continuation")
    assertTrue(
      Files.isRegularFile(fixtures.stateDb),
      "active goal *.db must survive untouched when the exit-64 guard fires",
    )
    assertTrue(Files.isRegularFile(fixtures.runtimeBin), "exit-64 guard must abort before any removal")
  }

  @Test
  fun `explicit uninstall fully removes the state dir even with copied source present`() {


    val fixtures = seedStateDirForWipe()
    val run = runUninstaller(fixtures, preserveSource = false, goalContinuation = false)

    assertEquals(0, run.exitCode, run.output)
    assertFalse(Files.exists(fixtures.stateDir), "explicit uninstall must fully remove ~/.skill-bill")
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

    assertFalse(run.output.contains("gradlew"), "prebuilt install must not call Gradle. Output:\n${run.output}")
    assertFalse(
      run.runtimeLog.contains("installDist"),
      "prebuilt install must not run a Gradle installDist build",
    )
    assertContains(run.output, "verified checksum:")
    assertContains(run.output, "Kotlin runtime installed from prebuilt release")

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

    assertFalse(
      Files.exists(run.home.resolve(".skill-bill/runtime/runtime-cli")),
      "no partial runtime-cli should be installed on checksum failure",
    )
  }

  @Test
  fun `from-source keeps gradle skip-build install behavior`() {


    val run = runInstallerShell(input = "1\nclaude\nbase only\noff\nskip\n", fromSource = true)

    assertContains(run.output, "Installing runtime from source (--from-source)")
    assertFalse(run.output.contains("verified checksum:"), "from-source must not verify release checksums")
  }

  @Test
  fun `prebuilt auto-falls back to source when host token has no matching asset`() {


    val run = runPrebuiltInstaller(releaseValid = true, options = PrebuiltOptions(omitRuntimeAssets = true))

    assertEquals(0, run.exitCode, run.output)
    assertContains(run.output, "falling back to a from-source Gradle build")
  }

  @Test
  fun `install plan summary is printed before any mutation`() {


    val run = runPrebuiltInstaller(
      releaseValid = true,
      options = PrebuiltOptions(skipPreinstallUninstall = false, seedPriorInstall = true),
    )

    assertEquals(0, run.exitCode, run.output)
    val planIndex = run.output.indexOf("What this installer will change")
    val cleanupIndex = run.output.indexOf("Pre-install cleanup")
    assertTrue(planIndex >= 0, "install plan must be printed. Output:\n${run.output}")
    assertTrue(cleanupIndex >= 0, "pre-install cleanup must run. Output:\n${run.output}")
    assertTrue(planIndex < cleanupIndex, "the plan must print before the first mutation")
    assertContains(run.output, "Reverse everything with:")
  }
}

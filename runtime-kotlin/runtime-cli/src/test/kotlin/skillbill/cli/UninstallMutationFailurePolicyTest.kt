package skillbill.cli

import com.github.ajalt.clikt.parsers.CommandLineParser
import skillbill.application.scaffold.InstallAgentService
import skillbill.application.system.UninstallFileSystemService
import skillbill.cli.kernel.CliRunState
import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliRunInputs
import skillbill.cli.model.ExternalCommandResult
import skillbill.cli.model.ExternalCommandRunner
import skillbill.cli.system.DesktopRemoval
import skillbill.cli.system.LauncherRemoval
import skillbill.cli.system.UninstallCommand
import skillbill.cli.system.UninstallDependencies
import skillbill.cli.system.UninstallMutationRecorder
import skillbill.cli.system.UninstallPlan
import skillbill.cli.system.cleanupMcpRegistrations
import skillbill.cli.system.removeLauncher
import skillbill.cli.system.removeRecursively
import skillbill.infrastructure.fs.CanonicalRepositoryRoot
import skillbill.install.model.McpMutationResult
import skillbill.ports.install.agent.InstallAgentTargetPort
import skillbill.ports.install.agent.model.ClaudeConfigRootsRequest
import skillbill.ports.install.agent.model.ClaudeConfigRootsResult
import skillbill.ports.install.agent.model.CodexConfigRootsRequest
import skillbill.ports.install.agent.model.CodexConfigRootsResult
import skillbill.ports.install.agent.model.DetectInstallAgentTargetsRequest
import skillbill.ports.install.agent.model.DetectInstallAgentTargetsResult
import skillbill.ports.install.agent.model.InstallAgentDirectoryRequest
import skillbill.ports.install.agent.model.InstallAgentDirectoryResult
import skillbill.ports.install.agent.model.InstallAgentPathRequest
import skillbill.ports.install.agent.model.InstallAgentPathResult
import skillbill.ports.install.agent.model.InstallAgentTargetCleanupRequest
import skillbill.ports.install.agent.model.InstallAgentTargetCleanupResult
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.mcp.model.InstallMcpRegistrationRequest
import skillbill.ports.install.mcp.model.InstallMcpRegistrationResult
import skillbill.ports.install.mcp.model.InstallMcpUnregistrationRequest
import skillbill.ports.install.model.InstallCleanupResult
import skillbill.ports.install.nativeagent.InstallNativeAgentLinkPort
import skillbill.ports.install.nativeagent.model.InstallNativeAgentLinkOperationRequest
import skillbill.ports.install.nativeagent.model.InstallNativeAgentLinkOperationResult
import skillbill.ports.install.nativeagent.model.InstallNativeAgentUnlinkOperationResult
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.system.UninstallPathsPort
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UninstallMutationFailurePolicyTest {
  @Test
  fun `failed launcher removal fails the outcome and records a degradation`() {
    val diagnostics = RecordingRuntimeDiagnostics()
    val recorder = UninstallMutationRecorder(diagnostics)

    removeLauncher(
      fileSystem = UninstallFileSystemService(ThrowingUninstallPathsPort(RUNTIME_BIN_PATH, "deleteIfExists")),
      launcher = LauncherRemoval(LAUNCHER_PATH, RUNTIME_BIN_PATH),
      removed = mutableListOf(),
      skipped = mutableListOf(),
      recorder = recorder,
    )

    assertTrue(recorder.failed())
    assertEquals(1, recorder.failureMessages().size, recorder.failureMessages().toString())
    assertEquals(1, diagnostics.errors.size, diagnostics.errors.toString())
    assertTrue(diagnostics.errors.single().contains(LAUNCHER_PATH.toString()), diagnostics.errors.toString())
  }

  @Test
  fun `failed state tree removal fails the outcome and records a degradation`() {
    val diagnostics = RecordingRuntimeDiagnostics()
    val recorder = UninstallMutationRecorder(diagnostics)

    removeRecursively(
      fileSystem = UninstallFileSystemService(ThrowingUninstallPathsPort(RUNTIME_BIN_PATH, "removeTree")),
      path = STATE_ROOT,
      removed = mutableListOf(),
      recorder = recorder,
    )

    assertTrue(recorder.failed())
    assertEquals(1, diagnostics.errors.size, diagnostics.errors.toString())
    assertTrue(diagnostics.errors.single().contains(STATE_ROOT.toString()), diagnostics.errors.toString())
  }

  @Test
  fun `failed MCP unregistration fails the outcome and records a degradation per agent`() {
    val diagnostics = RecordingRuntimeDiagnostics()
    val recorder = UninstallMutationRecorder(diagnostics)

    cleanupMcpRegistrations(
      plan = uninstallPlan(),
      installMcpRegistrationPort = ThrowingMcpRegistrationPort,
      removed = mutableListOf(),
      recorder = recorder,
    )

    assertTrue(recorder.failed())
    assertEquals(2, recorder.failureMessages().size, recorder.failureMessages().toString())
    assertTrue(diagnostics.errors.any { it.contains("claude") }, diagnostics.errors.toString())
    assertTrue(diagnostics.errors.any { it.contains("codex") }, diagnostics.errors.toString())
  }

  @Test
  fun `uninstall reports a failed mutation as a non-zero exit with a failed status`() {
    val result = runUninstall(ThrowingMcpRegistrationPort)

    assertEquals(1, result.exitCode, result.stdout)
    assertEquals("failed_with_degradations", result.payload?.get("status"), result.stdout)
    assertTrue(result.stdout.startsWith("uninstall_status: failed_with_degradations"), result.stdout)
  }

  @Test
  fun `uninstall without a failed mutation stays a zero exit`() {
    val result = runUninstall(SucceedingMcpRegistrationPort)

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("completed", result.payload?.get("status"), result.stdout)
  }

  private fun runUninstall(mcpRegistrationPort: InstallMcpRegistrationPort): CliExecutionResult {
    val state = CliRunState(stdinText = null)
    val command = UninstallCommand(
      state = state,
      inputs = CliRunInputs(
        dbPathOverride = null,
        stdinText = null,
        environment = emptyMap(),
        externalCommandRunner = ExternalCommandRunner { ExternalCommandResult(exitCode = 0, output = "") },
        userHome = HOME,
        repositoryRoot = HOME,
        repositoryEnclosingRootPort = CanonicalRepositoryRoot,
        liveStdout = {},
        liveStderr = {},
      ),
      deps = UninstallDependencies(
        installAgentService = InstallAgentService(StubInstallAgentTargetPort),
        installNativeAgentLinkPort = StubInstallNativeAgentLinkPort,
        installMcpRegistrationPort = mcpRegistrationPort,
        uninstallFileSystem = UninstallFileSystemService(AbsentUninstallPathsPort),
        hostPlatform = StubUninstallHostPlatformPort,
        diagnostics = RecordingRuntimeDiagnostics(),
      ),
    )
    CommandLineParser.parseAndRun(command, listOf("--yes")) { parsed -> parsed.run() }
    return assertNotNull(state.result)
  }

  private fun uninstallPlan(): UninstallPlan = UninstallPlan(
    home = HOME,
    stateRoot = STATE_ROOT,
    skillNames = emptyList(),
    legacyNames = emptyList(),
    agentTargets = emptyList(),
    nativeSourceRoots = emptyList(),
    mcpAgents = listOf("claude", "codex"),
    launchers = emptyList(),
    desktop = DesktopRemoval(launcher = null, files = emptyList(), directories = emptyList()),
  )

  private companion object {
    val HOME: Path = Path.of("/tmp/skillbill-uninstall-policy")
    val STATE_ROOT: Path = HOME.resolve(".skill-bill")
    val LAUNCHER_PATH: Path = HOME.resolve(".local/bin/skill-bill")
    val RUNTIME_BIN_PATH: Path = STATE_ROOT.resolve("runtime/runtime-cli/bin/runtime-cli")
  }
}

private class ThrowingUninstallPathsPort(
  private val symlinkTarget: Path,
  private val failingOperation: String,
) : UninstallPathsPort {
  override fun listImmediateDirectoryNames(root: Path): List<String> = emptyList()

  override fun exists(path: Path): Boolean = true

  override fun isSymbolicLink(path: Path): Boolean = true

  override fun readSymbolicLink(path: Path): Path = symlinkTarget

  override fun deleteIfExists(path: Path): Boolean =
    if (failingOperation == "deleteIfExists") throw IOException("permission denied") else true

  override fun removeTree(path: Path): List<Path> =
    if (failingOperation == "removeTree") throw IOException("directory not empty") else emptyList()
}

private object ThrowingMcpRegistrationPort : InstallMcpRegistrationPort {
  override fun registerMcp(request: InstallMcpRegistrationRequest): InstallMcpRegistrationResult =
    throw IOException("mcp config unwritable")

  override fun unregisterMcp(request: InstallMcpUnregistrationRequest): InstallMcpRegistrationResult =
    throw IOException("mcp config unwritable")
}

private val ABSENT_PATH: Path = Path.of("/tmp/skillbill-uninstall-policy/absent")

private object AbsentUninstallPathsPort : UninstallPathsPort {
  override fun listImmediateDirectoryNames(root: Path): List<String> = emptyList()

  override fun exists(path: Path): Boolean = false

  override fun isSymbolicLink(path: Path): Boolean = false

  override fun readSymbolicLink(path: Path): Path = path

  override fun deleteIfExists(path: Path): Boolean = false

  override fun removeTree(path: Path): List<Path> = emptyList()
}

private object StubInstallAgentTargetPort : InstallAgentTargetPort {
  override fun agentPath(request: InstallAgentPathRequest): InstallAgentPathResult = InstallAgentPathResult(ABSENT_PATH)

  override fun detectAgentTargets(request: DetectInstallAgentTargetsRequest): DetectInstallAgentTargetsResult =
    DetectInstallAgentTargetsResult(emptyList())

  override fun claudeConfigRoots(request: ClaudeConfigRootsRequest): ClaudeConfigRootsResult =
    ClaudeConfigRootsResult(emptyList())

  override fun codexConfigRoots(request: CodexConfigRootsRequest): CodexConfigRootsResult =
    CodexConfigRootsResult(emptyList())

  override fun agentDirectory(request: InstallAgentDirectoryRequest): InstallAgentDirectoryResult =
    InstallAgentDirectoryResult(ABSENT_PATH)

  override fun cleanupAgentTarget(request: InstallAgentTargetCleanupRequest): InstallAgentTargetCleanupResult =
    InstallAgentTargetCleanupResult(InstallCleanupResult(removed = emptyList(), skipped = emptyList()))
}

private object StubInstallNativeAgentLinkPort : InstallNativeAgentLinkPort {
  override fun linkNativeAgents(
    request: InstallNativeAgentLinkOperationRequest,
  ): InstallNativeAgentLinkOperationResult = throw UnsupportedOperationException("uninstall never links")

  override fun unlinkNativeAgents(
    request: InstallNativeAgentLinkOperationRequest,
  ): InstallNativeAgentUnlinkOperationResult = InstallNativeAgentUnlinkOperationResult(emptyList())
}

private object StubUninstallHostPlatformPort : HostPlatformPort {
  override val osName: String = "Linux"
  override val jvmClassPath: String = ""
  override val pathSeparator: String = ":"
}

private object SucceedingMcpRegistrationPort : InstallMcpRegistrationPort {
  override fun registerMcp(request: InstallMcpRegistrationRequest): InstallMcpRegistrationResult =
    throw UnsupportedOperationException("uninstall never registers")

  override fun unregisterMcp(request: InstallMcpUnregistrationRequest): InstallMcpRegistrationResult =
    InstallMcpRegistrationResult(
      McpMutationResult(agent = request.agent, configPath = ABSENT_PATH, changed = false),
    )
}

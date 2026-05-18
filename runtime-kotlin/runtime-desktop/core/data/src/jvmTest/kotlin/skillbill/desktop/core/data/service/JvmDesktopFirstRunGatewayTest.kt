package skillbill.desktop.core.data.service

import kotlinx.coroutines.runBlocking
import skillbill.desktop.core.domain.model.FirstRunApplyResult
import skillbill.desktop.core.domain.model.FirstRunInstallStatus
import skillbill.desktop.core.domain.model.FirstRunPlanResult
import skillbill.desktop.core.domain.model.FirstRunSetupRequest
import skillbill.desktop.core.domain.model.FirstRunTelemetryLevel
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallAppliedSkill
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallSkillStagingOutcome
import skillbill.install.model.InstallSkillStagingStatus
import skillbill.install.model.InstallStagingIntent
import skillbill.install.model.InstallTelemetryApplyOutcome
import skillbill.install.model.InstallTelemetryApplyStatus
import skillbill.install.model.McpRegistrationApplyOutcome
import skillbill.install.model.McpRegistrationApplyStatus
import skillbill.install.model.McpRegistrationIntent
import skillbill.install.model.NativeAgentApplyOutcome
import skillbill.install.model.NativeAgentApplyStatus
import skillbill.install.model.NativeAgentProviderId
import skillbill.install.model.PlannedPlatformPack
import skillbill.install.model.WindowsSymlinkApplyOutcome
import skillbill.install.model.WindowsSymlinkFallbackState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JvmDesktopFirstRunGatewayTest {
  @Test
  fun `plan maps desktop request into shared install plan request with MCP registration`() = runBlocking {
    val root = Files.createTempDirectory("skillbill-first-run-root")
    var capturedRequest: InstallPlanRequest? = null
    val gateway = JvmDesktopFirstRunGateway().apply {
      repoRootProvider = { root }
      homeProvider = { root.resolve("home") }
      runtimeAssetsProvider = { root.runtimeAssets() }
      planInstall = { request ->
        capturedRequest = request
        request.toPlan()
      }
    }

    val result = gateway.planSetup(
      FirstRunSetupRequest(
        selectedAgentIds = setOf("codex", "claude"),
        selectedPlatformSlugs = setOf("kotlin"),
        telemetryLevel = FirstRunTelemetryLevel.FULL,
        registerMcp = false,
      ),
    )

    assertIs<FirstRunPlanResult.Planned>(result)
    val request = checkNotNull(capturedRequest)
    assertEquals(InstallAgentSelectionMode.MANUAL, request.agentSelection.mode)
    assertEquals(setOf(InstallAgent.CLAUDE, InstallAgent.CODEX), request.agentSelection.manualAgents)
    assertEquals(setOf("kotlin"), request.platformPackSelection.selectedSlugs)
    assertEquals("full", request.telemetryLevel.id)
    assertTrue(request.mcpRegistrationChoice.register)
    assertEquals(
      root.resolve("runtime-mcp/bin/runtime-mcp").toAbsolutePath().normalize(),
      request.mcpRegistrationChoice.runtimeMcpBin,
    )
    assertEquals(root.resolve("skills").toAbsolutePath().normalize(), request.targetPaths.skillsRoot)
    assertEquals(root.resolve("platform-packs").toAbsolutePath().normalize(), request.targetPaths.platformPacksRoot)
    assertEquals(
      root.resolve("runtime-cli").toAbsolutePath().normalize(),
      request.runtimeDistributionInputs.runtimeCliBuildDir,
    )
    assertEquals(
      root.resolve("runtime-mcp").toAbsolutePath().normalize(),
      request.runtimeDistributionInputs.runtimeMcpBuildDir,
    )
    assertEquals(
      root.resolve("home/.skill-bill/runtime/runtime-cli"),
      request.runtimeDistributionInputs.runtimeCliInstallDir,
    )
    assertEquals(
      root.resolve("home/.skill-bill/runtime/runtime-mcp"),
      request.runtimeDistributionInputs.runtimeMcpInstallDir,
    )
    assertTrue(result.plan.platformPacks.single().selected)
    assertEquals(1, result.plan.baseSkillCount)
    assertEquals(1, result.plan.platformSkillCount)
  }

  @Test
  fun `plan uses bundled runtime assets from installed app locations`() = runBlocking {
    val root = Files.createTempDirectory("skillbill-installed-assets")
    val bundledRoot = root.resolve("app-resources/skill-bill-runtime")
    seedRuntimeAssetRoot(bundledRoot)
    var capturedRequest: InstallPlanRequest? = null
    val gateway = JvmDesktopFirstRunGateway().apply {
      homeProvider = { root.resolve("home") }
      runtimeAssetsProvider = {
        JvmRuntimeAssetLocator(
          userDirProvider = { root.resolve("elsewhere") },
          propertyProvider = { name ->
            if (name == JvmRuntimeAssetLocator.COMPOSE_RESOURCES_PROPERTY) {
              root.resolve("app-resources").toString()
            } else {
              null
            }
          },
          envProvider = { null },
        ).locate()
      }
      planInstall = { request ->
        capturedRequest = request
        request.toPlan()
      }
    }

    val result = gateway.planSetup(
      FirstRunSetupRequest(
        selectedAgentIds = setOf("codex"),
        selectedPlatformSlugs = setOf("python"),
        telemetryLevel = FirstRunTelemetryLevel.ANONYMOUS,
        registerMcp = true,
      ),
    )

    assertIs<FirstRunPlanResult.Planned>(result)
    val request = checkNotNull(capturedRequest)
    assertEquals(bundledRoot.toAbsolutePath().normalize(), request.repoRoot)
    assertEquals(bundledRoot.resolve("skills").toAbsolutePath().normalize(), request.targetPaths.skillsRoot)
    assertEquals(
      bundledRoot.resolve("platform-packs").toAbsolutePath().normalize(),
      request.targetPaths.platformPacksRoot,
    )
    assertEquals(
      bundledRoot.resolve("runtime-cli").toAbsolutePath().normalize(),
      request.runtimeDistributionInputs.runtimeCliBuildDir,
    )
    assertEquals(
      bundledRoot.resolve("runtime-mcp").toAbsolutePath().normalize(),
      request.runtimeDistributionInputs.runtimeMcpBuildDir,
    )
    assertEquals(
      bundledRoot.resolve("runtime-mcp/bin/runtime-mcp").toAbsolutePath().normalize(),
      request.mcpRegistrationChoice.runtimeMcpBin,
    )
  }

  @Test
  fun `apply maps structured backend warnings and outcomes`() = runBlocking {
    val root = Files.createTempDirectory("skillbill-first-run-apply")
    val gateway = JvmDesktopFirstRunGateway().apply {
      repoRootProvider = { root }
      homeProvider = { root.resolve("home") }
      runtimeAssetsProvider = { root.runtimeAssets() }
      planInstall = { request -> request.toPlan() }
      applyInstall = { plan ->
        InstallApplyResult(
          status = InstallApplyStatus.WARNING,
          skills = listOf(
            InstallAppliedSkill(
              skillName = "bill-feature-implement",
              kind = InstallPlanSkillKind.BASE,
              sourceDir = plan.request.targetPaths.skillsRoot.resolve("bill-feature-implement"),
              staging = InstallSkillStagingOutcome(
                status = InstallSkillStagingStatus.STAGED,
                sourceDir = plan.request.targetPaths.skillsRoot.resolve("bill-feature-implement"),
                stagingDir = plan.staging.root.resolve("bill-feature-implement-hash"),
              ),
            ),
          ),
          nativeAgents = listOf(
            NativeAgentApplyOutcome(
              provider = NativeAgentProviderId.CODEX,
              agent = InstallAgent.CODEX,
              status = NativeAgentApplyStatus.LINKED,
              path = root.resolve("home/.codex/agents/bill-review.md"),
              message = "Native agent linked.",
            ),
          ),
          telemetryOutcome = InstallTelemetryApplyOutcome(
            level = plan.telemetryLevel,
            status = InstallTelemetryApplyStatus.SUCCESS,
            configPath = root.resolve("home/.skill-bill/config.json"),
            message = "Telemetry level set to 'anonymous'.",
          ),
          mcpRegistrationOutcomes = listOf(
            McpRegistrationApplyOutcome(
              agent = InstallAgent.CODEX,
              status = McpRegistrationApplyStatus.FAILED,
              configPath = root.resolve("home/.codex/config.toml"),
              message = "MCP registration failed.",
              issue = InstallApplyIssue(
                kind = InstallApplyIssueKind.MCP_REGISTRATION_FAILED,
                message = "runtime-mcp missing",
                agent = InstallAgent.CODEX,
              ),
            ),
          ),
          warnings = listOf(
            InstallApplyIssue(
              kind = InstallApplyIssueKind.WINDOWS_SYMLINK_WARNING,
              message = "Windows requires Developer Mode.",
              guidance = "Enable Developer Mode or run elevated.",
            ),
          ),
          failures = emptyList(),
          windowsSymlinkOutcome = WindowsSymlinkApplyOutcome(
            preflight = plan.windowsSymlinkPreflight.copy(message = "Windows requires Developer Mode."),
            fallbackState = WindowsSymlinkFallbackState.PROCEEDING,
            guidance = "Enable Developer Mode or run elevated.",
          ),
          telemetryLevel = plan.telemetryLevel,
          mcpRegistrationIntent = plan.mcpRegistrationIntent,
        )
      }
    }
    val planned = assertIs<FirstRunPlanResult.Planned>(
      gateway.planSetup(
        FirstRunSetupRequest(
          selectedAgentIds = setOf("codex"),
          selectedPlatformSlugs = emptySet(),
          telemetryLevel = FirstRunTelemetryLevel.ANONYMOUS,
          registerMcp = true,
        ),
      ),
    )

    val applied = assertIs<FirstRunApplyResult.Applied>(gateway.applySetup(planned.plan))

    assertEquals(FirstRunInstallStatus.WARNING, applied.outcome.status)
    assertTrue(applied.outcome.details.any { detail -> detail.message == "Windows requires Developer Mode." })
    assertTrue(applied.outcome.details.any { detail -> detail.message == "Telemetry level set to 'anonymous'." })
    assertTrue(applied.outcome.details.any { detail -> detail.message == "MCP registration failed." })
    assertTrue(applied.outcome.details.any { detail -> detail.message == "Native agent linked." })
  }
}

class JvmRuntimeAssetLocatorTest {
  @Test
  fun `locates development checkout by walking up from user dir`() {
    val root = Files.createTempDirectory("skillbill-dev-runtime-assets")
    seedRuntimeAssetRoot(root)
    val nested = root.resolve("runtime-kotlin/runtime-desktop")
    Files.createDirectories(nested)

    val assets = JvmRuntimeAssetLocator(
      userDirProvider = { nested },
      propertyProvider = { null },
      envProvider = { null },
    ).locate()

    assertEquals(root.toAbsolutePath().normalize(), assets.root)
    assertEquals(root.resolve("skills").toAbsolutePath().normalize(), assets.skillsRoot)
    assertEquals(root.resolve("platform-packs").toAbsolutePath().normalize(), assets.platformPacksRoot)
    assertEquals(root.resolve("orchestration").toAbsolutePath().normalize(), assets.orchestrationRoot)
  }

  @Test
  fun `locates installed bundle below compose resources directory`() {
    val root = Files.createTempDirectory("skillbill-bundled-runtime-assets")
    val bundledRoot = root.resolve("resources/skill-bill-runtime")
    seedRuntimeAssetRoot(bundledRoot)

    val assets = JvmRuntimeAssetLocator(
      userDirProvider = { root.resolve("missing") },
      propertyProvider = { name ->
        if (name == JvmRuntimeAssetLocator.COMPOSE_RESOURCES_PROPERTY) {
          root.resolve("resources").toString()
        } else {
          null
        }
      },
      envProvider = { null },
    ).locate()

    assertEquals(bundledRoot.toAbsolutePath().normalize(), assets.root)
    assertEquals(bundledRoot.resolve("runtime-cli").toAbsolutePath().normalize(), assets.runtimeCliDir)
    assertEquals(bundledRoot.resolve("runtime-mcp").toAbsolutePath().normalize(), assets.runtimeMcpDir)
    assertEquals(
      bundledRoot.resolve("runtime-mcp/bin/runtime-mcp").toAbsolutePath().normalize(),
      assets.runtimeMcpBin(),
    )
  }

  @Test
  fun `explicit runtime assets property wins over development lookup`() {
    val devRoot = Files.createTempDirectory("skillbill-dev-runtime-assets")
    val explicitRoot = Files.createTempDirectory("skillbill-explicit-runtime-assets")
    seedRuntimeAssetRoot(devRoot)
    seedRuntimeAssetRoot(explicitRoot)

    val assets = JvmRuntimeAssetLocator(
      userDirProvider = { devRoot.resolve("runtime-kotlin") },
      propertyProvider = { name ->
        if (name == JvmRuntimeAssetLocator.ASSETS_DIR_PROPERTY) {
          explicitRoot.toString()
        } else {
          null
        }
      },
      envProvider = { null },
    ).locate()

    assertEquals(explicitRoot.toAbsolutePath().normalize(), assets.root)
  }
}

private fun InstallPlanRequest.toPlan(): InstallPlan {
  val agents = agentSelection.manualAgents.sortedBy(InstallAgent::id).map { agent ->
    InstallAgentTarget(
      agent = agent,
      path = home.resolve(".${agent.id}/agents"),
      source = InstallAgentTargetSource.MANUAL,
    )
  }
  val selectedPlatforms = platformPackSelection.selectedSlugs.sorted()
  val platformPackRoot = targetPaths.platformPacksRoot.resolve("kotlin")
  return InstallPlan(
    request = this,
    agents = agents,
    discoveredPlatformPacks = listOf(
      PlannedPlatformPack(
        slug = "kotlin",
        packRoot = platformPackRoot,
        selected = "kotlin" in selectedPlatforms,
      ),
    ),
    selectedPlatformSlugs = selectedPlatforms,
    skills = listOf(
      InstallPlanSkill(
        name = "bill-feature-implement",
        sourceDir = targetPaths.skillsRoot.resolve("bill-feature-implement"),
        kind = InstallPlanSkillKind.BASE,
      ),
      InstallPlanSkill(
        name = "bill-kotlin-code-review",
        sourceDir = platformPackRoot.resolve("bill-kotlin-code-review"),
        kind = InstallPlanSkillKind.PLATFORM_PACK,
        platformSlug = "kotlin",
      ),
    ),
    staging = InstallStagingIntent(
      root = home.resolve(".skill-bill/installed-skills"),
      skillPaths = emptyList(),
    ),
    telemetryLevel = telemetryLevel,
    mcpRegistrationIntent = McpRegistrationIntent(
      register = mcpRegistrationChoice.register,
      runtimeMcpBin = mcpRegistrationChoice.runtimeMcpBin,
      agents = agents.map(InstallAgentTarget::agent),
    ),
    runtimeDistributionInputs = runtimeDistributionInputs,
    installationTargetPaths = targetPaths.copy(agentTargets = agents),
    windowsSymlinkPreflight = windowsSymlinkPreflight,
  )
}

private fun Path.runtimeAssets(): DesktopRuntimeAssets {
  seedRuntimeAssetRoot(this)
  return JvmRuntimeAssetLocator(
    userDirProvider = { this },
    propertyProvider = { null },
    envProvider = { null },
  ).locate()
}

private fun seedRuntimeAssetRoot(root: Path) {
  Files.createDirectories(root.resolve("skills/bill-feature-implement"))
  Files.createDirectories(root.resolve("platform-packs/kotlin"))
  Files.createDirectories(root.resolve("platform-packs/python"))
  Files.createDirectories(root.resolve("orchestration"))
  Files.createDirectories(root.resolve("runtime-cli/bin"))
  Files.createDirectories(root.resolve("runtime-mcp/bin"))
  Files.createDirectories(root.resolve("runtime-kotlin/runtime-cli/build/install/runtime-cli/bin"))
  Files.createDirectories(root.resolve("runtime-kotlin/runtime-mcp/build/install/runtime-mcp/bin"))
  Files.writeString(root.resolve("skills/bill-feature-implement/content.md"), "# content\n")
  Files.writeString(root.resolve("platform-packs/kotlin/platform.yaml"), "platform: kotlin\n")
  Files.writeString(root.resolve("platform-packs/python/platform.yaml"), "platform: python\n")
  Files.writeString(root.resolve("runtime-mcp/bin/runtime-mcp"), "#!/usr/bin/env bash\n")
}

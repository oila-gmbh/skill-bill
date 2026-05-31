package skillbill.desktop.core.data.service

import kotlinx.coroutines.runBlocking
import skillbill.desktop.core.domain.model.FirstRunApplyResult
import skillbill.desktop.core.domain.model.FirstRunInstallStatus
import skillbill.desktop.core.domain.model.FirstRunPlanResult
import skillbill.desktop.core.domain.model.FirstRunPlatformSelectionMode
import skillbill.desktop.core.domain.model.FirstRunSetupRequest
import skillbill.desktop.core.domain.model.FirstRunTelemetryLevel
import skillbill.infrastructure.fs.FileSystemInstallSelectionPersistence
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
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.McpRegistrationApplyOutcome
import skillbill.install.model.McpRegistrationApplyStatus
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.McpRegistrationIntent
import skillbill.install.model.NativeAgentApplyOutcome
import skillbill.install.model.NativeAgentApplyStatus
import skillbill.install.model.NativeAgentProviderId
import skillbill.install.model.PlannedPlatformPack
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.SharedInstallSelection
import skillbill.install.model.WindowsSymlinkApplyOutcome
import skillbill.install.model.WindowsSymlinkFallbackState
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class JvmDesktopFirstRunGatewayTest {
  @Test
  fun `existing install is detected from staged skill cache`() {
    val home = Files.createTempDirectory("skillbill-first-run-installed")
    val stagedSkill = home.resolve(".skill-bill/installed-skills/bill-code-review-0123456789abcdef")
    Files.createDirectories(stagedSkill)
    Files.writeString(stagedSkill.resolve("SKILL.md"), "# bill-code-review\n")
    Files.writeString(stagedSkill.resolve(".content-hash"), "0123456789abcdef")
    val gateway = JvmDesktopFirstRunGateway().apply {
      homeProvider = { home }
    }

    assertTrue(gateway.hasExistingInstall())
  }

  @Test
  fun `existing install ignores incomplete staging residue`() {
    val home = Files.createTempDirectory("skillbill-first-run-empty")
    val stagedSkill = home.resolve(".skill-bill/installed-skills/.staging-tmp-1")
    Files.createDirectories(stagedSkill)
    Files.writeString(stagedSkill.resolve(".content-hash"), "0123456789abcdef")
    val gateway = JvmDesktopFirstRunGateway().apply {
      homeProvider = { home }
    }

    assertFalse(gateway.hasExistingInstall())
  }

  @Test
  fun `latest reusable setup request reads shared install selection`() {
    val home = Files.createTempDirectory("skillbill-first-run-shared-selection")
    FileSystemInstallSelectionPersistence().writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(
        installHome = home,
        selection = SharedInstallSelection(
          selectedAgents = setOf(InstallAgent.CLAUDE, InstallAgent.CODEX),
          platformPackSelection = PlatformPackSelection(
            mode = PlatformPackSelectionMode.SELECTED,
            selectedSlugs = setOf("kotlin"),
          ),
          telemetryLevel = InstallTelemetryLevel.FULL,
          mcpRegistrationChoice = McpRegistrationChoice(register = false),
        ),
      ),
    )
    val gateway = JvmDesktopFirstRunGateway().apply {
      homeProvider = { home }
    }

    val request = gateway.latestReusableSetupRequest()

    assertEquals(setOf("claude", "codex"), request?.selectedAgentIds)
    assertEquals(setOf("kotlin"), request?.selectedPlatformSlugs)
    assertEquals(FirstRunPlatformSelectionMode.SELECTED, request?.platformSelectionMode)
    assertEquals(FirstRunTelemetryLevel.FULL, request?.telemetryLevel)
    assertFalse(request?.registerMcp ?: true)
  }

  @Test
  fun `latest reusable setup request prefers shared selection over legacy fallback`() {
    val home = Files.createTempDirectory("skillbill-first-run-shared-before-legacy")
    val store = FileSystemInstallSelectionPersistence()
    store.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(
        installHome = home,
        selection = SharedInstallSelection(
          selectedAgents = setOf(InstallAgent.OPENCODE),
          platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.ALL),
          telemetryLevel = InstallTelemetryLevel.OFF,
          mcpRegistrationChoice = McpRegistrationChoice(register = false),
        ),
      ),
    )
    val legacy = FirstRunSetupRequest(
      selectedAgentIds = setOf("codex"),
      selectedPlatformSlugs = setOf("kotlin"),
      telemetryLevel = FirstRunTelemetryLevel.FULL,
      registerMcp = true,
    )
    val gateway = JvmDesktopFirstRunGateway().apply {
      homeProvider = { home }
    }

    val request = gateway.latestReusableSetupRequest(legacy)
    val persisted = store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home)).selection

    assertEquals(setOf("opencode"), request?.selectedAgentIds)
    assertEquals(emptySet(), request?.selectedPlatformSlugs)
    assertEquals(FirstRunPlatformSelectionMode.ALL, request?.platformSelectionMode)
    assertEquals(FirstRunTelemetryLevel.OFF, request?.telemetryLevel)
    assertFalse(request?.registerMcp ?: true)
    assertEquals(setOf(InstallAgent.OPENCODE), persisted.selectedAgents)
    assertEquals(PlatformPackSelectionMode.ALL, persisted.platformPackSelection.mode)
    assertFalse(persisted.mcpRegistrationChoice.register)
  }

  @Test
  fun `latest reusable setup request migrates legacy fallback into shared store`() {
    val home = Files.createTempDirectory("skillbill-first-run-legacy-selection")
    val legacy = FirstRunSetupRequest(
      selectedAgentIds = setOf("codex"),
      selectedPlatformSlugs = setOf("kotlin"),
      telemetryLevel = FirstRunTelemetryLevel.OFF,
      registerMcp = false,
    )
    val store = FileSystemInstallSelectionPersistence()
    val gateway = JvmDesktopFirstRunGateway().apply {
      homeProvider = { home }
    }

    val request = gateway.latestReusableSetupRequest(legacy)
    val migrated = store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home)).selection

    assertEquals(legacy, request)
    assertEquals(setOf(InstallAgent.CODEX), migrated.selectedAgents)
    assertEquals(PlatformPackSelectionMode.SELECTED, migrated.platformPackSelection.mode)
    assertEquals(setOf("kotlin"), migrated.platformPackSelection.selectedSlugs)
    assertEquals(InstallTelemetryLevel.OFF, migrated.telemetryLevel)
    assertFalse(migrated.mcpRegistrationChoice.register)
  }

  @Test
  fun `plan maps desktop request into shared install plan request with MCP preference`() = runBlocking {
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
    assertFalse(request.mcpRegistrationChoice.register)
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
  fun `default apply writes telemetry config under planned home`() = runBlocking {
    val root = Files.createTempDirectory("skillbill-first-run-home-bound-apply")
    val plannedHome = root.resolve("planned-home")
    val processHome = root.resolve("process-home")
    val originalUserHome = System.getProperty("user.home")
    try {
      System.setProperty("user.home", processHome.toString())
      val gateway = JvmDesktopFirstRunGateway().apply {
        homeProvider = { plannedHome }
        runtimeAssetsProvider = { root.runtimeAssets() }
        planInstall = { request -> request.toTelemetryOnlyPlan() }
      }
      val planned = assertIs<FirstRunPlanResult.Planned>(
        gateway.planSetup(
          FirstRunSetupRequest(
            selectedAgentIds = emptySet(),
            selectedPlatformSlugs = emptySet(),
            telemetryLevel = FirstRunTelemetryLevel.FULL,
            registerMcp = false,
          ),
        ),
      )

      val applyResult = gateway.applySetup(planned.plan)
      if (applyResult is FirstRunApplyResult.Failed) {
        fail(
          applyResult.outcome.details.joinToString("; ") { detail ->
            "${detail.label}: ${detail.message}"
          },
        )
      }
      val applied = assertIs<FirstRunApplyResult.Applied>(applyResult)

      assertEquals(FirstRunInstallStatus.SUCCESS, applied.outcome.status)
      assertTrue(Files.readString(plannedHome.resolve(".skill-bill/config.json")).contains("\"level\":\"full\""))
      assertFalse(
        Files.exists(processHome.resolve(".skill-bill/config.json")),
        "default apply must not mutate process user.home",
      )
    } finally {
      System.setProperty("user.home", originalUserHome)
    }
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
              skillName = "bill-feature-task",
              kind = InstallPlanSkillKind.BASE,
              sourceDir = plan.request.targetPaths.skillsRoot.resolve("bill-feature-task"),
              staging = InstallSkillStagingOutcome(
                status = InstallSkillStagingStatus.STAGED,
                sourceDir = plan.request.targetPaths.skillsRoot.resolve("bill-feature-task"),
                stagingDir = plan.staging.root.resolve("bill-feature-task-hash"),
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
        name = "bill-feature-task",
        sourceDir = targetPaths.skillsRoot.resolve("bill-feature-task"),
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

private fun InstallPlanRequest.toTelemetryOnlyPlan(): InstallPlan {
  val emptySkillsRoot = home.resolve(".skill-bill/test-empty-skills")
  val emptyPlatformPacksRoot = home.resolve(".skill-bill/test-empty-platform-packs")
  Files.createDirectories(emptySkillsRoot)
  Files.createDirectories(emptyPlatformPacksRoot)
  return InstallPlan(
    request = this,
    agents = emptyList(),
    discoveredPlatformPacks = emptyList(),
    selectedPlatformSlugs = emptyList(),
    skills = emptyList(),
    staging = InstallStagingIntent(
      root = home.resolve(".skill-bill/installed-skills"),
      skillPaths = emptyList(),
    ),
    telemetryLevel = telemetryLevel,
    mcpRegistrationIntent = McpRegistrationIntent(
      register = false,
      runtimeMcpBin = null,
      agents = emptyList(),
    ),
    runtimeDistributionInputs = runtimeDistributionInputs,
    installationTargetPaths = targetPaths.copy(
      skillsRoot = emptySkillsRoot,
      platformPacksRoot = emptyPlatformPacksRoot,
      agentTargets = emptyList(),
    ),
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
  Files.createDirectories(root.resolve("skills/bill-feature-task"))
  Files.createDirectories(root.resolve("platform-packs/kotlin"))
  Files.createDirectories(root.resolve("platform-packs/python"))
  Files.createDirectories(root.resolve("orchestration"))
  Files.createDirectories(root.resolve("runtime-cli/bin"))
  Files.createDirectories(root.resolve("runtime-mcp/bin"))
  Files.createDirectories(root.resolve("runtime-kotlin/runtime-cli/build/install/runtime-cli/bin"))
  Files.createDirectories(root.resolve("runtime-kotlin/runtime-mcp/build/install/runtime-mcp/bin"))
  Files.writeString(root.resolve("skills/bill-feature-task/content.md"), "# content\n")
  Files.writeString(root.resolve("platform-packs/kotlin/platform.yaml"), "platform: kotlin\n")
  Files.writeString(root.resolve("platform-packs/python/platform.yaml"), "platform: python\n")
  Files.writeString(root.resolve("runtime-mcp/bin/runtime-mcp"), "#!/usr/bin/env bash\n")
}

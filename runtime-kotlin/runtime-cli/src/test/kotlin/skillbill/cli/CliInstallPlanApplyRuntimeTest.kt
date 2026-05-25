package skillbill.cli

import skillbill.contracts.JsonSupport
import skillbill.db.DatabaseRuntime
import skillbill.db.DbConstants
import skillbill.db.TelemetryOutboxStore
import skillbill.error.InvalidInstallPlanSchemaError
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallStagingIntent
import skillbill.install.model.InstallStagingPathIntent
import skillbill.install.model.InstallTelemetryApplyOutcome
import skillbill.install.model.InstallTelemetryApplyStatus
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.McpRegistrationIntent
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.WindowsSymlinkApplyOutcome
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkFallbackState
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliInstallPlanApplyRuntimeTest {
  @Test
  fun `install plan maps manual agents platforms telemetry and mcp choices`() {
    val fixture = installPlanApplyFixture()

    val result = CliRuntime.run(manualPlanArguments(fixture), CliRuntimeContext(userHome = fixture.home))

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeInstallPlanApplyJson(result.stdout)
    assertEquals("planned", payload["status"])
    val agentsByName = payload.agents().associateBy { agent -> agent["agent"] as String }
    assertEquals(supportedAgents, agentsByName.keys)
    assertTrue(payload.agents().all { agent -> agent["source"] == "manual" })
    assertEquals(fixture.home.resolve(".copilot/skills").toString(), agentsByName.getValue("copilot")["path"])
    assertEquals(fixture.home.resolve(".claude/commands").toString(), agentsByName.getValue("claude")["path"])
    assertEquals(fixture.home.resolve(".agents/skills").toString(), agentsByName.getValue("codex")["path"])
    assertEquals(fixture.home.resolve(".config/opencode/skills").toString(), agentsByName.getValue("opencode")["path"])
    assertEquals(fixture.home.resolve(".junie/skills").toString(), agentsByName.getValue("junie")["path"])
    assertEquals(listOf("kotlin"), payload["selected_platforms"])
    val packsBySlug = payload.listOfMaps("platform_packs").associateBy { pack -> pack["slug"] as String }
    assertEquals(setOf("kmp", "kotlin"), packsBySlug.keys)
    assertEquals(false, packsBySlug.getValue("kmp")["selected"])
    assertEquals(true, packsBySlug.getValue("kotlin")["selected"])
    assertPlannedSkillKinds(payload)
    assertEquals("full", payload["telemetry_level"])
    val mcp = payload.mapValue("mcp_registration")
    assertEquals(true, mcp["register"])
    assertEquals("/tmp/runtime-mcp", mcp["runtime_mcp_bin"])
    assertEquals(supportedAgents, (mcp["agents"] as List<*>).toSet())
    val runtime = payload.mapValue("runtime_distribution")
    assertEquals(fixture.home.resolve(".skill-bill/runtime").toString(), runtime["runtime_install_root"])
    assertEquals(null, runtime["runtime_cli_build_dir"])
    assertEquals(null, runtime["runtime_mcp_build_dir"])
    assertEquals(false, payload["replace_existing_skill_bill_links"])
    val preflight = payload.mapValue("windows_symlink_preflight")
    assertEquals("requires_elevation_or_developer_mode", preflight["state"])
    assertEquals("proceed_with_symlinks", preflight["decision"])
    assertEquals("Windows symlink support was not confirmed.", preflight["message"])
  }

  @Test
  fun `install plan maps detected agents all platforms and telemetry levels`() {
    listOf("anonymous", "full", "off").forEach { telemetry ->
      val fixture = installPlanApplyFixture()
      createDetectedAgentHomes(fixture.home)

      val result = CliRuntime.run(detectedPlanArguments(fixture, telemetry), CliRuntimeContext(userHome = fixture.home))

      assertEquals(0, result.exitCode, "$telemetry: ${result.stdout}")
      val payload = decodeInstallPlanApplyJson(result.stdout)
      assertEquals(telemetry, payload["telemetry_level"])
      assertEquals(false, payload.mapValue("mcp_registration")["register"])
      assertEquals(null, payload.mapValue("mcp_registration")["runtime_mcp_bin"])
      assertEquals(supportedAgents, payload.agents().map { agent -> agent["agent"] }.toSet())
      assertTrue(payload.agents().all { agent -> agent["source"] == "detected" })
      assertEquals(setOf("kmp", "kotlin"), (payload["selected_platforms"] as List<*>).toSet())
      assertTrue(payload.listOfMaps("platform_packs").all { pack -> pack["selected"] == true })
      assertTrue(payload.listOfMaps("skills").any { skill -> skill["name"] == "bill-code-review" })
      assertTrue(payload.listOfMaps("skills").any { skill -> skill["name"] == "bill-quality-check" })
      assertTrue(payload.listOfMaps("skills").any { skill -> skill["name"] == "bill-kotlin-code-review" })
    }
  }

  @Test
  fun `install apply maps all manual agent targets base staging telemetry and skipped mcp`() {
    val fixture = installPlanApplyFixture()
    val targetsByAgent = supportedAgents.associateWith { agent -> fixture.home.resolve("manual-targets/$agent") }

    val result = CliRuntime.run(
      manualBaseOnlyApplyArguments(fixture, targetsByAgent),
      CliRuntimeContext(userHome = fixture.home),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeInstallPlanApplyJson(result.stdout)
    assertEquals("success", payload["status"])
    assertEquals(emptyList<String>(), payload["selected_platforms"])
    assertEquals(supportedAgents, payload.agents().map { agent -> agent["agent"] }.toSet())
    payload.agents().forEach { agent ->
      val agentName = agent["agent"] as String
      assertEquals(targetsByAgent.getValue(agentName).toString(), agent["path"])
      assertEquals("manual", agent["source"])
    }
    val stagingRoot = fixture.home.resolve(".skill-bill/installed-skills").toString()
    assertEquals(stagingRoot, payload["staging_root"])
    assertTrue(
      payload.listOfMaps("staging").all { staging -> staging["staging_dir"].toString().startsWith(stagingRoot) },
    )
    val skills = payload.listOfMaps("skills")
    assertEquals(setOf("bill-code-review", "bill-quality-check"), skills.map { skill -> skill["name"] }.toSet())
    assertTrue(skills.all { skill -> skill["kind"] == "base" })
    assertTrue(skills.all { skill -> skill.mapValue("staging")["status"] == "staged" })
    val links = skills.flatMap { skill -> skill.listOfMaps("links") }
    assertEquals(supportedAgents.size * skills.size, links.size)
    assertEquals(supportedAgents, links.map { link -> link["agent"] }.toSet())
    supportedAgents.forEach { agent ->
      val agentLinks = links.filter { link -> link["agent"] == agent }
      val targetDir = targetsByAgent.getValue(agent).toString()
      assertEquals(skills.size, agentLinks.size)
      assertTrue(agentLinks.all { link -> link["target_dir"] == targetDir })
      assertEquals(
        skills.map { skill -> "$targetDir/${skill["name"]}" }.toSet(),
        agentLinks.map { link -> link["link_path"] }.toSet(),
      )
    }
    assertTrue(links.all { link -> link["status"] == "created" })
    assertTrue(links.all { link -> link["link_target"].toString().startsWith(stagingRoot) })
    val telemetry = payload.mapValue("telemetry")
    assertEquals("anonymous", telemetry["level"])
    assertEquals("success", telemetry["status"])
    val mcp = payload.mapValue("mcp_registration")
    assertEquals(false, mcp["register"])
    assertEquals(supportedAgents, (mcp["agents"] as List<*>).toSet())
    assertTrue(mcp.listOfMaps("outcomes").all { outcome -> outcome["status"] == "skipped" })

    val selection = readInstallSelection(fixture.home)
    assertEquals(supportedAgents, (selection["selected_agents"] as List<*>).toSet())
    assertEquals("none", selection.mapValue("platform_pack_selection")["mode"])
    assertEquals(emptyList<String>(), selection.mapValue("platform_pack_selection")["selected_slugs"])
    assertEquals("anonymous", selection["telemetry_level"])
    assertEquals(false, selection.mapValue("mcp_registration")["register"])
    assertEquals(null, selection.mapValue("mcp_registration")["runtime_mcp_bin"])
  }

  @Test
  fun `install apply telemetry uses parsed home and db overrides`() {
    val componentHome = Files.createTempDirectory("skillbill-cli-install-component-home")
    val fixture = installPlanApplyFixture()
    val overrideDb = Files.createTempFile("skillbill-cli-install-override", ".db")
    val componentDb = DbConstants.defaultDbPath(componentHome)
    val componentConfig = writeTelemetryConfig(componentHome, "full")
    val overrideConfig = writeTelemetryConfig(fixture.home, "full")
    enqueueTelemetryEvent(componentDb)
    enqueueTelemetryEvent(overrideDb)

    val result = CliRuntime.run(
      listOf(
        "--home",
        fixture.home.toString(),
        "--db",
        overrideDb.toString(),
        "install",
        "apply",
        "--repo-root",
        fixture.repoRoot.toString(),
        "--agent-mode",
        "manual",
        "--agent",
        "codex",
        "--agent-target",
        "codex=${fixture.home.resolve("manual-targets/codex")}",
        "--platform-mode",
        "none",
        "--telemetry",
        "off",
        "--mcp",
        "skip",
        "--format",
        "json",
      ),
      CliRuntimeContext(userHome = componentHome, environment = emptyMap()),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeInstallPlanApplyJson(result.stdout)
    val telemetry = payload.mapValue("telemetry")
    assertEquals("off", telemetry["level"])
    assertEquals("success", telemetry["status"])
    assertEquals(overrideConfig.toString(), telemetry["config_path"])
    assertEquals(1, telemetry["cleared_events"])
    assertFalse(Files.exists(overrideConfig))
    assertTrue(Files.exists(componentConfig))
    assertEquals(0, pendingTelemetryEvents(overrideDb))
    assertEquals(1, pendingTelemetryEvents(componentDb))
  }

  @Test
  fun `install apply links selected and all platform pack payloads through cli contract`() {
    val selectedFixture = installPlanApplyFixture()
    assertApplyPlatformSkills(
      payload = decodeInstallPlanApplyJson(
        CliRuntime.run(
          singleCodexApplyArguments(selectedFixture, platformMode = "selected", platforms = listOf("kotlin")),
          CliRuntimeContext(userHome = selectedFixture.home),
        ).also { result -> assertEquals(0, result.exitCode, result.stdout) }.stdout,
      ),
      expectedSelectedPlatforms = setOf("kotlin"),
      expectedSkillNames = setOf(
        "bill-code-review",
        "bill-quality-check",
        "bill-kotlin-code-review",
        "bill-kotlin-quality-check",
      ),
      targetDir = selectedFixture.home.resolve("manual-targets/codex"),
    )

    val allFixture = installPlanApplyFixture()
    assertApplyPlatformSkills(
      payload = decodeInstallPlanApplyJson(
        CliRuntime.run(
          singleCodexApplyArguments(allFixture, platformMode = "all"),
          CliRuntimeContext(userHome = allFixture.home),
        ).also { result -> assertEquals(0, result.exitCode, result.stdout) }.stdout,
      ),
      expectedSelectedPlatforms = setOf("kmp", "kotlin"),
      expectedSkillNames = setOf(
        "bill-code-review",
        "bill-quality-check",
        "bill-kmp-code-review",
        "bill-kmp-quality-check",
        "bill-kotlin-code-review",
        "bill-kotlin-quality-check",
      ),
      targetDir = allFixture.home.resolve("manual-targets/codex"),
    )
  }

  @Test
  fun `install apply persists detected agents platform packs telemetry and mcp choice`() {
    val fixture = installPlanApplyFixture()
    createDetectedAgentHomes(fixture.home)

    val result = CliRuntime.run(detectedApplyArguments(fixture), CliRuntimeContext(userHome = fixture.home))

    assertEquals(0, result.exitCode, result.stdout)
    val selection = readInstallSelection(fixture.home)
    assertEquals(supportedAgents, (selection["selected_agents"] as List<*>).toSet())
    val platformSelection = selection.mapValue("platform_pack_selection")
    assertEquals("all", platformSelection["mode"])
    assertEquals(emptyList<String>(), platformSelection["selected_slugs"])
    assertEquals("off", selection["telemetry_level"])
    val mcp = selection.mapValue("mcp_registration")
    assertEquals(false, mcp["register"])
    assertEquals(null, mcp["runtime_mcp_bin"])
  }

  @Test
  fun `install apply persists manual selected platforms and mcp registration choice`() {
    val fixture = installPlanApplyFixture()
    val runtimeMcpBin = fixture.home.resolve(".skill-bill/runtime/runtime-mcp/bin/runtime-mcp")

    val result = CliRuntime.run(
      manualSelectedPlatformRegisterMcpApplyArguments(fixture, runtimeMcpBin),
      CliRuntimeContext(userHome = fixture.home),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val selection = readInstallSelection(fixture.home)
    assertEquals(setOf("codex"), (selection["selected_agents"] as List<*>).toSet())
    val platformSelection = selection.mapValue("platform_pack_selection")
    assertEquals("selected", platformSelection["mode"])
    assertEquals(listOf("kotlin"), platformSelection["selected_slugs"])
    assertEquals("full", selection["telemetry_level"])
    val mcp = selection.mapValue("mcp_registration")
    assertEquals(true, mcp["register"])
    assertEquals(runtimeMcpBin.toString(), mcp["runtime_mcp_bin"])
  }

  @Test
  fun `install apply parses replace existing flag and removes legacy managed targets`() {
    val fixture = installPlanApplyFixture()
    val targetDir = fixture.home.resolve("manual-targets/codex")
    val legacyManaged = targetDir.resolve("mdp-gcheck")
    val stalePlatformManaged = targetDir.resolve("bill-kotlin-code-review")
    listOf(legacyManaged, stalePlatformManaged).forEach { managed ->
      Files.createDirectories(managed)
      Files.writeString(managed.resolve(".skill-bill-install"), "")
      Files.writeString(managed.resolve("SKILL.md"), "old managed install")
    }

    val result = CliRuntime.run(
      singleCodexApplyArguments(
        fixture = fixture,
        platformMode = "none",
        extraArgs = listOf("--replace-existing-skill-bill-links"),
      ),
      CliRuntimeContext(userHome = fixture.home),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertFalse(Files.exists(legacyManaged))
    assertFalse(Files.exists(stalePlatformManaged))
    val payload = decodeInstallPlanApplyJson(result.stdout)
    assertEquals("success", payload["status"])
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-code-review")))
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-quality-check")))
  }

  @Test
  fun `install apply renders structured windows symlink preflight failure`() {
    val fixture = installPlanApplyFixture()

    val result = CliRuntime.run(windowsFailureApplyArguments(fixture), CliRuntimeContext(userHome = fixture.home))

    assertEquals(1, result.exitCode, result.stdout)
    val payload = decodeInstallPlanApplyJson(result.stdout)
    assertEquals("failure", payload["status"])
    val outcome = payload.mapValue("windows_symlink_outcome")
    assertEquals("user_action_required", outcome["fallback_state"])
    val preflight = outcome.mapValue("preflight")
    assertEquals("decision_required", preflight["state"])
    assertEquals("require_user_action", preflight["decision"])
    val failures = payload.listOfMaps("failures")
    assertEquals("windows_symlink_precheck_failed", failures.single()["kind"])
    assertEquals(
      "Windows requires elevation or Developer Mode before symlink install.",
      failures.single()["message"],
    )
    assertFalse(Files.exists(installSelectionPath(fixture.home)))
  }

  @Test
  fun `cli plan and apply seams loud fail typed install-plan schema errors`() {
    val fixture = installPlanApplyFixture()
    val invalidPlan = invalidCliInstallPlan(fixture)

    val planError = assertFailsWith<InvalidInstallPlanSchemaError> {
      installPlanPayload(invalidPlan)
    }
    assertContains(planError.message.orEmpty(), "mcp_registration.runtime_mcp_bin")

    val applyError = assertFailsWith<InvalidInstallPlanSchemaError> {
      installApplyPayload(invalidPlan, minimalApplyResult(invalidPlan))
    }
    assertContains(applyError.message.orEmpty(), "mcp_registration.runtime_mcp_bin")
  }

  @Test
  fun `install plan and apply JSON preserve byte-equivalent wire ordering`() {
    val fixture = installByteEquivalenceFixture()

    val planResult = CliRuntime.run(singleCodexPlanArguments(fixture), CliRuntimeContext(userHome = fixture.home))
    assertEquals(0, planResult.exitCode, planResult.stdout)
    assertEquals(CliOutput.emit(singleCodexPlanGoldenPayload(fixture), CliFormat.JSON), planResult.stdout)

    val applyResult = CliRuntime.run(
      singleCodexApplyArguments(fixture, platformMode = "none"),
      CliRuntimeContext(userHome = fixture.home),
    )
    assertEquals(0, applyResult.exitCode, applyResult.stdout)
    assertEquals(CliOutput.emit(singleCodexApplyGoldenPayload(fixture), CliFormat.JSON), applyResult.stdout)
  }

  private fun assertPlannedSkillKinds(payload: Map<String, Any?>) {
    val skills = payload.listOfMaps("skills").associateBy { skill -> skill["name"] }
    assertEquals("base", skills.getValue("bill-code-review")["kind"])
    assertEquals("base", skills.getValue("bill-quality-check")["kind"])
    assertEquals("platform_pack", skills.getValue("bill-kotlin-code-review")["kind"])
    assertEquals("platform_pack", skills.getValue("bill-kotlin-quality-check")["kind"])
  }

  private fun assertApplyPlatformSkills(
    payload: Map<String, Any?>,
    expectedSelectedPlatforms: Set<String>,
    expectedSkillNames: Set<String>,
    targetDir: Path,
  ) {
    assertEquals("success", payload["status"])
    assertEquals(expectedSelectedPlatforms, (payload["selected_platforms"] as List<*>).toSet())
    val skills = payload.listOfMaps("skills")
    assertEquals(expectedSkillNames, skills.map { skill -> skill["name"] }.toSet())
    assertTrue(skills.all { skill -> skill.mapValue("staging")["status"] == "staged" })
    val links = skills.flatMap { skill -> skill.listOfMaps("links") }
    assertEquals(expectedSkillNames.size, links.size)
    assertTrue(links.all { link -> link["agent"] == "codex" })
    assertTrue(links.all { link -> link["target_dir"] == targetDir.toString() })
    assertEquals(
      expectedSkillNames.map { skillName -> targetDir.resolve(skillName).toString() }.toSet(),
      links.map { link -> link["link_path"] }.toSet(),
    )
    assertTrue(links.all { link -> link["status"] == "created" })
  }

  private fun manualPlanArguments(fixture: InstallPlanApplyFixture): List<String> = listOf(
    "install",
    "plan",
    "--repo-root",
    fixture.repoRoot.toString(),
    "--agent-mode",
    "manual",
    "--agent",
    "copilot",
    "--agent",
    "claude",
    "--agent",
    "codex",
    "--agent",
    "opencode",
    "--agent",
    "junie",
    "--platform",
    "kotlin",
    "--telemetry",
    "full",
    "--mcp",
    "register",
    "--runtime-mcp-bin",
    "/tmp/runtime-mcp",
    "--windows-symlink-state",
    "requires-elevation-or-developer-mode",
    "--windows-symlink-decision",
    "proceed-with-symlinks",
    "--windows-symlink-message",
    "Windows symlink support was not confirmed.",
    "--format",
    "json",
  )

  private fun manualBaseOnlyApplyArguments(
    fixture: InstallPlanApplyFixture,
    targetsByAgent: Map<String, Path>,
  ): List<String> = buildList {
    add("install")
    add("apply")
    add("--repo-root")
    add(fixture.repoRoot.toString())
    add("--agent-mode")
    add("manual")
    supportedAgents.sorted().forEach { agent ->
      add("--agent")
      add(agent)
      add("--agent-target")
      add("$agent=${targetsByAgent.getValue(agent)}")
    }
    add("--platform-mode")
    add("none")
    add("--telemetry")
    add("anonymous")
    add("--mcp")
    add("skip")
    add("--format")
    add("json")
  }

  private fun singleCodexApplyArguments(
    fixture: InstallPlanApplyFixture,
    platformMode: String,
    platforms: List<String> = emptyList(),
    extraArgs: List<String> = emptyList(),
  ): List<String> = buildList {
    add("install")
    add("apply")
    add("--repo-root")
    add(fixture.repoRoot.toString())
    add("--agent-mode")
    add("manual")
    add("--agent")
    add("codex")
    add("--agent-target")
    add("codex=${fixture.home.resolve("manual-targets/codex")}")
    add("--platform-mode")
    add(platformMode)
    platforms.forEach { platform ->
      add("--platform")
      add(platform)
    }
    add("--telemetry")
    add("anonymous")
    add("--mcp")
    add("skip")
    addAll(extraArgs)
    add("--format")
    add("json")
  }

  private fun detectedPlanArguments(fixture: InstallPlanApplyFixture, telemetry: String): List<String> = listOf(
    "install",
    "plan",
    "--repo-root",
    fixture.repoRoot.toString(),
    "--agent-mode",
    "detected",
    "--platform-mode",
    "all",
    "--telemetry",
    telemetry,
    "--mcp",
    "skip",
    "--format",
    "json",
  )

  private fun detectedApplyArguments(fixture: InstallPlanApplyFixture): List<String> = listOf(
    "install",
    "apply",
    "--repo-root",
    fixture.repoRoot.toString(),
    "--agent-mode",
    "detected",
    "--platform-mode",
    "all",
    "--telemetry",
    "off",
    "--mcp",
    "skip",
    "--format",
    "json",
  )

  private fun manualSelectedPlatformRegisterMcpApplyArguments(
    fixture: InstallPlanApplyFixture,
    runtimeMcpBin: Path,
  ): List<String> = listOf(
    "install",
    "apply",
    "--repo-root",
    fixture.repoRoot.toString(),
    "--agent-mode",
    "manual",
    "--agent",
    "codex",
    "--agent-target",
    "codex=${fixture.home.resolve("manual-targets/codex")}",
    "--platform-mode",
    "selected",
    "--platform",
    "kotlin",
    "--telemetry",
    "full",
    "--mcp",
    "register",
    "--runtime-mcp-bin",
    runtimeMcpBin.toString(),
    "--format",
    "json",
  )

  private fun windowsFailureApplyArguments(fixture: InstallPlanApplyFixture): List<String> = listOf(
    "install",
    "apply",
    "--repo-root",
    fixture.repoRoot.toString(),
    "--agent-mode",
    "manual",
    "--agent",
    "codex",
    "--platform-mode",
    "none",
    "--mcp",
    "skip",
    "--windows-symlink-state",
    "decision-required",
    "--windows-symlink-decision",
    "require-user-action",
    "--windows-symlink-message",
    "Windows requires elevation or Developer Mode before symlink install.",
    "--format",
    "json",
  )

  private fun singleCodexPlanArguments(fixture: InstallPlanApplyFixture): List<String> = listOf(
    "install",
    "plan",
    "--repo-root",
    fixture.repoRoot.toString(),
    "--agent-mode",
    "manual",
    "--agent",
    "codex",
    "--agent-target",
    "codex=${fixture.home.resolve("manual-targets/codex")}",
    "--platform-mode",
    "none",
    "--telemetry",
    "anonymous",
    "--mcp",
    "skip",
    "--format",
    "json",
  )
}

private val supportedAgents = setOf("copilot", "claude", "codex", "opencode", "junie")

private fun installPlanApplyFixture(): InstallPlanApplyFixture {
  val home = Files.createTempDirectory("skillbill-cli-install-plan-apply-home")
  val repoRoot = Files.createTempDirectory("skillbill-cli-install-plan-apply-repo")
  seedCliBaseSkill(repoRoot, "bill-code-review")
  seedCliBaseSkill(repoRoot, "bill-quality-check")
  seedCliPlatformPack(repoRoot, "kmp")
  seedCliPlatformPack(repoRoot, "kotlin")
  return InstallPlanApplyFixture(repoRoot = repoRoot, home = home)
}

private fun installByteEquivalenceFixture(): InstallPlanApplyFixture {
  val home = Files.createTempDirectory("skillbill-cli-install-byte-equivalence-home")
  val repoRoot = Files.createTempDirectory("skillbill-cli-install-byte-equivalence-repo")
  Files.createDirectories(repoRoot.resolve("platform-packs"))
  seedCliBaseSkill(repoRoot, "bill-code-review")
  seedCliBaseSkill(repoRoot, "bill-quality-check")
  return InstallPlanApplyFixture(repoRoot = repoRoot, home = home)
}

private fun invalidCliInstallPlan(fixture: InstallPlanApplyFixture): InstallPlan {
  val target = codexInstallTarget(fixture)
  val sourceDir = fixture.repoRoot.resolve("skills/bill-code-review")
  val request = invalidCliInstallRequest(fixture, target)
  val stagingRoot = fixture.home.resolve(".skill-bill/installed-skills")
  return InstallPlan(
    request = request,
    agents = listOf(target),
    discoveredPlatformPacks = emptyList(),
    selectedPlatformSlugs = emptyList(),
    skills = listOf(baseInstallPlanSkill(sourceDir)),
    staging = invalidCliStagingIntent(stagingRoot, sourceDir),
    telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
    mcpRegistrationIntent = invalidMcpRegistrationIntent(),
    runtimeDistributionInputs = request.runtimeDistributionInputs,
    installationTargetPaths = request.targetPaths,
    windowsSymlinkPreflight = request.windowsSymlinkPreflight,
  )
}

private fun codexInstallTarget(fixture: InstallPlanApplyFixture): InstallAgentTarget = InstallAgentTarget(
  agent = InstallAgent.CODEX,
  path = fixture.home.resolve("manual-targets/codex"),
  source = InstallAgentTargetSource.MANUAL,
)

private fun invalidCliInstallRequest(
  fixture: InstallPlanApplyFixture,
  target: InstallAgentTarget,
): InstallPlanRequest = InstallPlanRequest(
  repoRoot = fixture.repoRoot,
  home = fixture.home,
  agentSelection = InstallAgentSelection(
    mode = InstallAgentSelectionMode.MANUAL,
    manualAgents = setOf(InstallAgent.CODEX),
  ),
  platformPackSelection = PlatformPackSelection(PlatformPackSelectionMode.NONE),
  telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
  mcpRegistrationChoice = McpRegistrationChoice(register = true, runtimeMcpBin = Path.of("")),
  runtimeDistributionInputs = RuntimeDistributionInputs(
    runtimeInstallRoot = fixture.home.resolve(".skill-bill/runtime"),
  ),
  targetPaths = InstallationTargetPaths(
    skillsRoot = fixture.repoRoot.resolve("skills"),
    platformPacksRoot = fixture.repoRoot.resolve("platform-packs"),
    agentTargets = listOf(target),
  ),
  windowsSymlinkPreflight = WindowsSymlinkPreflight(
    state = WindowsSymlinkPreflightState.NOT_WINDOWS,
    decision = WindowsSymlinkDecision.NOT_REQUIRED,
  ),
)

private fun baseInstallPlanSkill(sourceDir: Path): InstallPlanSkill = InstallPlanSkill(
  name = "bill-code-review",
  sourceDir = sourceDir,
  kind = InstallPlanSkillKind.BASE,
)

private fun invalidCliStagingIntent(stagingRoot: Path, sourceDir: Path): InstallStagingIntent = InstallStagingIntent(
  root = stagingRoot,
  skillPaths = listOf(
    InstallStagingPathIntent(
      skillName = "bill-code-review",
      sourceDir = sourceDir,
      stagingRoot = stagingRoot,
      stagingDir = stagingRoot.resolve("bill-code-review-testhash"),
      contentHash = "testhash",
    ),
  ),
)

private fun invalidMcpRegistrationIntent(): McpRegistrationIntent = McpRegistrationIntent(
  register = true,
  runtimeMcpBin = Path.of(""),
  agents = listOf(InstallAgent.CODEX),
)

private fun minimalApplyResult(plan: InstallPlan): InstallApplyResult = InstallApplyResult(
  status = InstallApplyStatus.SUCCESS,
  skills = emptyList(),
  nativeAgents = emptyList(),
  telemetryOutcome = InstallTelemetryApplyOutcome(
    level = plan.telemetryLevel,
    status = InstallTelemetryApplyStatus.SKIPPED,
  ),
  mcpRegistrationOutcomes = emptyList(),
  warnings = emptyList(),
  failures = emptyList(),
  windowsSymlinkOutcome = WindowsSymlinkApplyOutcome(
    preflight = plan.windowsSymlinkPreflight,
    fallbackState = WindowsSymlinkFallbackState.NOT_REQUIRED,
  ),
  telemetryLevel = plan.telemetryLevel,
  mcpRegistrationIntent = plan.mcpRegistrationIntent,
)

private fun seedCliBaseSkill(repoRoot: Path, skillName: String) {
  val skillDir = repoRoot.resolve("skills").resolve(skillName)
  Files.createDirectories(skillDir)
  Files.writeString(skillDir.resolve("content.md"), cliSkillContent(skillName))
}

private fun seedCliPlatformPack(repoRoot: Path, slug: String) {
  val codeReviewName = "bill-$slug-code-review"
  val qualityCheckName = "bill-$slug-quality-check"
  val packRoot = repoRoot.resolve("platform-packs").resolve(slug)
  Files.createDirectories(packRoot.resolve("code-review").resolve(codeReviewName))
  Files.createDirectories(packRoot.resolve("quality-check").resolve(qualityCheckName))
  Files.writeString(packRoot.resolve("platform.yaml"), cliPlatformManifest(slug, codeReviewName, qualityCheckName))
  Files.writeString(
    packRoot.resolve("code-review").resolve(codeReviewName).resolve("content.md"),
    cliSkillContent(codeReviewName),
  )
  Files.writeString(
    packRoot.resolve("quality-check").resolve(qualityCheckName).resolve("content.md"),
    cliSkillContent(qualityCheckName),
  )
}

private fun cliPlatformManifest(slug: String, codeReviewName: String, qualityCheckName: String): String = """
  |platform: "$slug"
  |contract_version: "1.1"
  |routing_signals:
  |  strong:
  |    - "$slug"
  |  tie_breakers: []
  |declared_code_review_areas: []
  |declared_files:
  |  baseline: "code-review/$codeReviewName/content.md"
  |  areas: {}
  |area_metadata: {}
  |display_name: "$slug"
  |declared_quality_check_file: "quality-check/$qualityCheckName/content.md"
  |
""".trimMargin()

private fun cliSkillContent(skillName: String): String = """
  |---
  |name: $skillName
  |description: Test skill.
  |---
  |
  |# $skillName
  |
  |Test body.
  |
""".trimMargin()

private fun createDetectedAgentHomes(home: Path) {
  Files.createDirectories(home.resolve(".copilot"))
  Files.createDirectories(home.resolve(".claude"))
  Files.createDirectories(home.resolve(".codex"))
  Files.createDirectories(home.resolve(".config/opencode"))
  Files.createDirectories(home.resolve(".junie"))
}

private fun writeTelemetryConfig(home: Path, level: String): Path {
  val configPath = home.resolve(".skill-bill/config.json").toAbsolutePath().normalize()
  Files.createDirectories(configPath.parent)
  Files.writeString(
    configPath,
    """
    |{"install_id":"test-install","telemetry":{"level":"$level","batch_size":100}}
    |
    """.trimMargin(),
  )
  return configPath
}

private fun enqueueTelemetryEvent(dbPath: Path) {
  DatabaseRuntime.openDb(cliValue = dbPath.toString(), environment = emptyMap()).use { db ->
    TelemetryOutboxStore(db.connection).enqueue("test.event", """{"ok":true}""")
  }
}

private fun pendingTelemetryEvents(dbPath: Path): Int =
  DatabaseRuntime.openDb(cliValue = dbPath.toString(), environment = emptyMap()).use { db ->
    TelemetryOutboxStore(db.connection).pendingCount()
  }

private data class InstallPlanApplyFixture(
  val repoRoot: Path,
  val home: Path,
)

private fun decodeInstallPlanApplyJson(rawJson: String): Map<String, Any?> =
  JsonSupport.anyToStringAnyMap(JsonSupport.parseObjectOrNull(rawJson)?.let(JsonSupport::jsonElementToValue))
    ?: emptyMap()

private fun readInstallSelection(home: Path): Map<String, Any?> =
  decodeInstallPlanApplyJson(Files.readString(installSelectionPath(home)))

private fun installSelectionPath(home: Path): Path = home.resolve(".skill-bill/install-selection.json")

private fun singleCodexPlanGoldenPayload(fixture: InstallPlanApplyFixture): Map<String, Any?> {
  val paths = SingleCodexGoldenPaths(fixture)
  return mapOf(
    "status" to "planned",
    "contract_version" to "0.1",
    "agents" to listOf(codexAgentGoldenPayload(paths)),
    "platform_packs" to emptyList<Map<String, Any?>>(),
    "selected_platforms" to emptyList<String>(),
    "skills" to listOf(
      plannedSkillGoldenPayload("bill-code-review", paths.codeReviewSourceDir),
      plannedSkillGoldenPayload("bill-quality-check", paths.qualityCheckSourceDir),
    ),
    "staging_root" to paths.stagingRoot,
    "staging" to listOf(
      stagingIntentGoldenPayload(
        skillName = "bill-code-review",
        sourceDir = paths.codeReviewSourceDir,
        stagingDir = paths.codeReviewStagingDir,
        contentHash = CODE_REVIEW_GOLDEN_CONTENT_HASH,
      ),
      stagingIntentGoldenPayload(
        skillName = "bill-quality-check",
        sourceDir = paths.qualityCheckSourceDir,
        stagingDir = paths.qualityCheckStagingDir,
        contentHash = QUALITY_CHECK_GOLDEN_CONTENT_HASH,
      ),
    ),
    "telemetry_level" to "anonymous",
    "mcp_registration" to mapOf(
      "register" to false,
      "runtime_mcp_bin" to null,
      "agents" to listOf("codex"),
    ),
    "runtime_distribution" to runtimeDistributionGoldenPayload(paths),
    "windows_symlink_preflight" to windowsPreflightGoldenPayload(),
    "replace_existing_skill_bill_links" to false,
  )
}

private fun singleCodexApplyGoldenPayload(fixture: InstallPlanApplyFixture): Map<String, Any?> {
  val paths = SingleCodexGoldenPaths(fixture)
  return singleCodexPlanGoldenPayload(fixture) + mapOf(
    "status" to "success",
    "skills" to appliedSkillsGoldenPayload(paths),
    "native_agents" to nativeAgentsGoldenPayload(),
    "telemetry" to telemetryGoldenPayload(paths),
    "mcp_registration" to applyMcpRegistrationGoldenPayload(),
    "warnings" to emptyList<Map<String, Any?>>(),
    "failures" to emptyList<Map<String, Any?>>(),
    "windows_symlink_outcome" to windowsSymlinkOutcomeGoldenPayload(),
  )
}

private fun appliedSkillsGoldenPayload(paths: SingleCodexGoldenPaths): List<Map<String, Any?>> = listOf(
  appliedSkillGoldenPayload(
    AppliedSkillGoldenValues(
      skillName = "bill-code-review",
      sourceDir = paths.codeReviewSourceDir,
      stagingDir = paths.codeReviewStagingDir,
      renderedSkillFile = paths.codeReviewRenderedSkillFile,
      contentHash = CODE_REVIEW_GOLDEN_CONTENT_HASH,
      targetDir = paths.codexTargetDir,
      linkPath = paths.codeReviewLinkPath,
    ),
  ),
  appliedSkillGoldenPayload(
    AppliedSkillGoldenValues(
      skillName = "bill-quality-check",
      sourceDir = paths.qualityCheckSourceDir,
      stagingDir = paths.qualityCheckStagingDir,
      renderedSkillFile = paths.qualityCheckRenderedSkillFile,
      contentHash = QUALITY_CHECK_GOLDEN_CONTENT_HASH,
      targetDir = paths.codexTargetDir,
      linkPath = paths.qualityCheckLinkPath,
    ),
  ),
)

private fun nativeAgentsGoldenPayload(): List<Map<String, Any?>> = listOf(
  mapOf(
    "provider" to "codex",
    "agent" to "codex",
    "status" to "skipped",
    "path" to null,
    "message" to "no native-agent target or artifacts available for selected plan",
    "issue" to null,
  ),
)

private fun telemetryGoldenPayload(paths: SingleCodexGoldenPaths): Map<String, Any?> = mapOf(
  "level" to "anonymous",
  "status" to "success",
  "config_path" to paths.telemetryConfigPath,
  "cleared_events" to 0,
  "message" to "Telemetry level set to 'anonymous'.",
  "issue" to null,
)

private fun applyMcpRegistrationGoldenPayload(): Map<String, Any?> = mapOf(
  "register" to false,
  "runtime_mcp_bin" to null,
  "agents" to listOf("codex"),
  "outcomes" to listOf(
    mapOf(
      "agent" to "codex",
      "status" to "skipped",
      "config_path" to null,
      "changed" to false,
      "message" to "MCP registration not requested.",
      "issue" to null,
    ),
  ),
)

private fun windowsSymlinkOutcomeGoldenPayload(): Map<String, Any?> = mapOf(
  "preflight" to windowsPreflightGoldenPayload(),
  "fallback_state" to "not_required",
  "guidance" to WINDOWS_SYMLINK_GUIDANCE,
)

private fun codexAgentGoldenPayload(paths: SingleCodexGoldenPaths): Map<String, Any?> = mapOf(
  "agent" to "codex",
  "path" to paths.codexTargetDir,
  "source" to "manual",
)

private fun plannedSkillGoldenPayload(skillName: String, sourceDir: String): Map<String, Any?> = mapOf(
  "name" to skillName,
  "kind" to "base",
  "platform" to null,
  "source_dir" to sourceDir,
)

private fun appliedSkillGoldenPayload(values: AppliedSkillGoldenValues): Map<String, Any?> =
  plannedSkillGoldenPayload(values.skillName, values.sourceDir) + mapOf(
    "staging" to mapOf(
      "status" to "staged",
      "staging_dir" to values.stagingDir,
      "rendered_skill_file" to values.renderedSkillFile,
      "content_hash" to values.contentHash,
      "issue" to null,
    ),
    "links" to listOf(
      mapOf(
        "agent" to "codex",
        "target_dir" to values.targetDir,
        "link_path" to values.linkPath,
        "link_target" to values.stagingDir,
        "status" to "created",
        "message" to "linked to ${values.stagingDir}",
        "issue" to null,
      ),
    ),
  )

private data class AppliedSkillGoldenValues(
  val skillName: String,
  val sourceDir: String,
  val stagingDir: String,
  val renderedSkillFile: String,
  val contentHash: String,
  val targetDir: String,
  val linkPath: String,
)

private fun stagingIntentGoldenPayload(
  skillName: String,
  sourceDir: String,
  stagingDir: String,
  contentHash: String,
): Map<String, Any?> = mapOf(
  "skill_name" to skillName,
  "source_dir" to sourceDir,
  "staging_dir" to stagingDir,
  "content_hash" to contentHash,
)

private fun runtimeDistributionGoldenPayload(paths: SingleCodexGoldenPaths): Map<String, Any?> = mapOf(
  "runtime_install_root" to paths.runtimeInstallRoot,
  "runtime_cli_build_dir" to null,
  "runtime_mcp_build_dir" to null,
  "runtime_cli_install_dir" to null,
  "runtime_mcp_install_dir" to null,
  "runtime_launcher_bin_dir" to null,
)

private fun windowsPreflightGoldenPayload(): Map<String, Any?> = mapOf(
  "state" to "not_windows",
  "decision" to "not_required",
  "message" to "",
)

private data class SingleCodexGoldenPaths(private val fixture: InstallPlanApplyFixture) {
  val codexTargetDir: String = fixture.home.resolve("manual-targets/codex").toString()
  val stagingRoot: String = fixture.home.resolve(".skill-bill/installed-skills").toString()
  val runtimeInstallRoot: String = fixture.home.resolve(".skill-bill/runtime").toString()
  val telemetryConfigPath: String = fixture.home.resolve(".skill-bill/config.json").toString()
  val codeReviewSourceDir: String = fixture.repoRoot.resolve("skills/bill-code-review").toString()
  val qualityCheckSourceDir: String = fixture.repoRoot.resolve("skills/bill-quality-check").toString()
  val codeReviewStagingDir: String = stagingDir("bill-code-review", CODE_REVIEW_GOLDEN_CONTENT_HASH)
  val qualityCheckStagingDir: String = stagingDir("bill-quality-check", QUALITY_CHECK_GOLDEN_CONTENT_HASH)
  val codeReviewRenderedSkillFile: String = Path.of(codeReviewStagingDir).resolve("SKILL.md").toString()
  val qualityCheckRenderedSkillFile: String = Path.of(qualityCheckStagingDir).resolve("SKILL.md").toString()
  val codeReviewLinkPath: String = fixture.home.resolve("manual-targets/codex/bill-code-review").toString()
  val qualityCheckLinkPath: String = fixture.home.resolve("manual-targets/codex/bill-quality-check").toString()

  private fun stagingDir(skillName: String, contentHash: String): String =
    fixture.home.resolve(".skill-bill/installed-skills").resolve("$skillName-$contentHash").toString()
}

private const val CODE_REVIEW_GOLDEN_CONTENT_HASH = "f8ac2740c5fa2af0"
private const val QUALITY_CHECK_GOLDEN_CONTENT_HASH = "24c743bc4dd89750"
private const val WINDOWS_SYMLINK_GUIDANCE =
  "On Windows, enable Developer Mode (Settings -> Privacy & security -> For developers) or run the install command " +
    "from an elevated shell so the JVM can create symlinks."

private fun Map<String, Any?>.mapValue(key: String): Map<String, Any?> = requireStringAnyMap(get(key), key)

private fun Map<String, Any?>.listOfMaps(key: String): List<Map<String, Any?>> {
  val rawValue = get(key)
  require(rawValue is List<*>) { "Expected '$key' to be a list." }
  return rawValue.mapIndexed { index, item -> requireStringAnyMap(item, "$key[$index]") }
}

private fun Map<String, Any?>.agents(): List<Map<String, Any?>> = listOfMaps("agents")

private fun requireStringAnyMap(rawValue: Any?, label: String): Map<String, Any?> {
  require(rawValue is Map<*, *>) { "Expected '$label' to be an object." }
  return rawValue.entries.associate { (key, value) ->
    require(key is String) { "Expected '$label' keys to be strings." }
    key to value
  }
}

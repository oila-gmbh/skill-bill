package skillbill.cli

import skillbill.contracts.JsonSupport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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

private data class InstallPlanApplyFixture(
  val repoRoot: Path,
  val home: Path,
)

private fun decodeInstallPlanApplyJson(rawJson: String): Map<String, Any?> =
  JsonSupport.anyToStringAnyMap(JsonSupport.parseObjectOrNull(rawJson)?.let(JsonSupport::jsonElementToValue))
    ?: emptyMap()

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

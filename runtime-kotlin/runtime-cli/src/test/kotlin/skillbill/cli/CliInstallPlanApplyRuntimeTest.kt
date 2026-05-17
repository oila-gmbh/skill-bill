package skillbill.cli

import skillbill.contracts.JsonSupport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliInstallPlanApplyRuntimeTest {
  @Test
  fun `install plan maps manual agents platforms telemetry and mcp choices`() {
    val fixture = installPlanApplyFixture()

    val result = CliRuntime.run(manualPlanArguments(fixture), CliRuntimeContext(userHome = fixture.home))

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeInstallPlanApplyJson(result.stdout)
    assertEquals("planned", payload["status"])
    assertEquals(supportedAgents, payload.agents().map { agent -> agent["agent"] }.toSet())
    assertTrue(payload.agents().all { agent -> agent["source"] == "manual" })
    assertEquals(listOf("kotlin"), payload["selected_platforms"])
    assertPlannedSkillKinds(payload)
    assertEquals("full", payload["telemetry_level"])
    val mcp = payload.mapValue("mcp_registration")
    assertEquals(true, mcp["register"])
    assertEquals("/tmp/runtime-mcp", mcp["runtime_mcp_bin"])
    val preflight = payload.mapValue("windows_symlink_preflight")
    assertEquals("requires_elevation_or_developer_mode", preflight["state"])
    assertEquals("proceed_with_symlinks", preflight["decision"])
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
      assertEquals(supportedAgents, payload.agents().map { agent -> agent["agent"] }.toSet())
      assertTrue(payload.agents().all { agent -> agent["source"] == "detected" })
      assertEquals(listOf("kotlin"), payload["selected_platforms"])
      assertTrue(payload.listOfMaps("skills").any { skill -> skill["name"] == "bill-code-review" })
      assertTrue(payload.listOfMaps("skills").any { skill -> skill["name"] == "bill-kotlin-code-review" })
    }
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

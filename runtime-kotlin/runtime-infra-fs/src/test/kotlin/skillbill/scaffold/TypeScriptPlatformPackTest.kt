package skillbill.scaffold

import skillbill.error.InvalidManifestSchemaError
import skillbill.error.InvalidNativeAgentCompositionSchemaError
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallSkillStagingStatus
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.NativeAgentApplyStatus
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.install.runtime.InstallOperations
import skillbill.nativeagent.composition.composeNativeAgentSource
import skillbill.nativeagent.composition.parseNativeAgentBundle
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeScriptPlatformPackTest {
  @Test
  fun `typescript platform pack declares expected manifest and routing contract`() {
    val packRoot = repoRootFromTest().resolve("platform-packs/typescript")
    val pack = loadPlatformPack(packRoot)

    assertEquals("typescript", pack.slug)
    assertEquals("TypeScript", pack.displayName)
    assertEquals("1.2", pack.contractVersion)
    assertEquals(packRoot.resolve("code-review/bill-typescript-code-review/content.md"), pack.declaredFiles.baseline)
    assertEquals(packRoot.resolve("quality-check/bill-typescript-code-check/content.md"), pack.declaredQualityCheckFile)
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.declaredFiles.areas.keys)
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.areaMetadata.keys)
    listOf(
      "tsconfig.json",
      "tsconfig.*.json",
      "*.ts",
      "*.tsx",
      "*.mts",
      "*.cts",
      "package.json",
      "package-lock.json",
      "yarn.lock",
      "pnpm-lock.yaml",
      "bun.lockb",
      "biome.json",
      "eslint.config.*",
      ".eslintrc*",
      "prettier.config.*",
      ".prettierrc*",
    ).forEach { marker -> assertContains(pack.routingSignals.strong, marker) }
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("package.json or a lockfile alone") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("generated API clients") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("ambient *.d.ts") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("node_modules/") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("generated declaration") })
  }

  @Test
  fun `typescript review quality and native agent sources are complete`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/typescript")
    val contentFiles = Files.walk(packRoot).use { stream ->
      stream.filter { path -> path.fileName.toString() == "content.md" }.sorted().toList()
    }
    assertEquals(12, contentFiles.size)
    contentFiles.forEach { contentFile ->
      val text = Files.readString(contentFile)
      assertContains(text, "internal-for:")
      assertFalse(text.contains("TODO"), "TypeScript pack source must not contain TODO placeholders: $contentFile")
      assertTrue(text.lines().size > 18, "TypeScript pack source should contain substantive guidance: $contentFile")
    }

    val bundlePath = packRoot.resolve("code-review/bill-typescript-code-review/native-agents/agents.yaml")
    assertContains(Files.readString(bundlePath), "contract_version: \"0.1\"")
    val agents = parseNativeAgentBundle(bundlePath)
    val expectedNames = APPROVED_CODE_REVIEW_AREAS.map { "bill-typescript-code-review-$it" }.toSet() +
      "bill-typescript-code-review"
    assertEquals(expectedNames, agents.map { it.name }.toSet())
    agents.forEach { agent ->
      val composed = composeNativeAgentSource(repoRoot, agent)
      assertTrue(composed.body.isNotBlank(), "Expected governed content for ${agent.name}")
    }
  }

  @Test
  fun `typescript pack loader preserves partial extension bundles`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-typescript-pack-malformed-")
    val packRoot = tempRoot.resolve("typescript")
    copyDirectory(repoRoot.resolve("platform-packs/typescript"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-typescript-code-review/native-agents/agents.yaml")
    Files.writeString(
      bundle,
      Files.readString(bundle).replace(
        Regex("(?ms)  - name: bill-typescript-code-review-security\\n.*?(?=  - name:|\\z)"),
        "",
      ),
    )

    assertEquals("typescript", loadPlatformPack(packRoot).slug)
  }

  @Test
  fun `typescript pack rejects renamed governed content agents`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-typescript-pack-all-agents-renamed-")
    val packRoot = tempRoot.resolve("typescript")
    copyDirectory(repoRoot.resolve("platform-packs/typescript"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-typescript-code-review/native-agents/agents.yaml")
    val canonicalNames = APPROVED_CODE_REVIEW_AREAS.map { "bill-typescript-code-review-$it" } +
      "bill-typescript-code-review"
    val renamedBundle = canonicalNames.fold(Files.readString(bundle)) { content, name ->
      content.replace("name: $name\n", "name: renamed-$name\n")
    }
    val reducedRenamedBundle = listOf(
      "renamed-bill-typescript-code-review",
      "renamed-bill-typescript-code-review-security",
    ).fold(renamedBundle) { content, name ->
      content.replace(Regex("(?ms)  - name: $name\\n.*?(?=  - name:|\\z)"), "")
    }
    Files.writeString(bundle, reducedRenamedBundle)

    val error = assertFailsWith<InvalidManifestSchemaError> { loadPlatformPack(packRoot) }
    assertContains(error.message.orEmpty(), "unknown=[renamed-bill-typescript-code-review-api-contracts")
    assertContains(error.message.orEmpty(), "renamed-bill-typescript-code-review")
  }

  @Test
  fun `typescript pack accepts custom body based native agent`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-typescript-pack-unknown-body-agent-")
    val packRoot = tempRoot.resolve("typescript")
    copyDirectory(repoRoot.resolve("platform-packs/typescript"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-typescript-code-review/native-agents/agents.yaml")
    Files.writeString(
      bundle,
      Files.readString(bundle) +
        "  - name: undeclared-typescript-reviewer\n" +
        "    description: \"Custom review agent.\"\n" +
        "    body: |-\n" +
        "      Review the diff.\n",
    )

    assertEquals("typescript", loadPlatformPack(packRoot).slug)
  }

  @Test
  fun `typescript pack loader accepts a specialist-only extension bundle`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-typescript-pack-missing-baseline-")
    val packRoot = tempRoot.resolve("typescript")
    copyDirectory(repoRoot.resolve("platform-packs/typescript"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-typescript-code-review/native-agents/agents.yaml")
    Files.writeString(
      bundle,
      Files.readString(bundle).replace(
        Regex("(?ms)  - name: bill-typescript-code-review\\n.*?(?=  - name:|\\z)"),
        "",
      ),
    )

    assertEquals("typescript", loadPlatformPack(packRoot).slug)
  }

  @Test
  fun `typescript pack reports malformed native agent yaml as typed contract failure`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-typescript-pack-malformed-agent-yaml-")
    val packRoot = tempRoot.resolve("typescript")
    copyDirectory(repoRoot.resolve("platform-packs/typescript"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-typescript-code-review/native-agents/agents.yaml")
    Files.writeString(bundle, "agents:\n  - name: [unterminated\n")

    val error = assertFailsWith<InvalidNativeAgentCompositionSchemaError> { loadPlatformPack(packRoot) }
    assertContains(error.sourceLabel, bundle.toString())
    assertContains(error.reason, "could not parse YAML")
  }

  @Test
  fun `typescript pack accepts a custom baseline native agent description`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-typescript-pack-custom-baseline-description-")
    val packRoot = tempRoot.resolve("typescript")
    copyDirectory(repoRoot.resolve("platform-packs/typescript"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-typescript-code-review/native-agents/agents.yaml")
    Files.writeString(
      bundle,
      Files.readString(bundle).replace(
        "TypeScript baseline code reviewer. Reviews the full owned diff before specialist findings are merged. " +
          "Returns an F-XXX Risk Register.",
        "Team-owned TypeScript baseline reviewer.",
      ),
    )

    assertEquals("typescript", loadPlatformPack(packRoot).slug)
  }

  @Test
  fun `install apply stages selected typescript pack skills and native agents`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-typescript-install-plan-home-")
    val plan = typescriptInstallPlan(repoRoot, home)

    val skillsByName = plan.skills.associateBy { it.name }
    assertContains(plan.discoveredPlatformPacks.map { it.slug }, "typescript")
    assertEquals(listOf("typescript"), plan.selectedPlatformSlugs)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-typescript-code-review").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-typescript-code-check").kind)
    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-typescript-code-review-$area").kind)
    }
    assertFalse(skillsByName.containsKey("bill-go-code-review"))
    assertFalse(skillsByName.containsKey("bill-python-code-review"))

    assertTypeScriptInstallApplied(plan)
  }

  private fun typescriptInstallPlan(repoRoot: Path, home: Path): InstallPlan {
    val runtimeInstallRoot = home.resolve(".skill-bill/runtime")
    return InstallOperations.planInstall(
      InstallPlanRequest(
        repoRoot = repoRoot,
        home = home,
        agentSelection = InstallAgentSelection(
          mode = InstallAgentSelectionMode.MANUAL,
          manualAgents = setOf(InstallAgent.CODEX),
        ),
        platformPackSelection = PlatformPackSelection(
          mode = PlatformPackSelectionMode.SELECTED,
          selectedSlugs = setOf("typescript"),
        ),
        telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
        mcpRegistrationChoice = McpRegistrationChoice(
          register = false,
          runtimeMcpBin = runtimeInstallRoot.resolve("runtime-mcp/bin/runtime-mcp"),
        ),
        runtimeDistributionInputs = RuntimeDistributionInputs(runtimeInstallRoot = runtimeInstallRoot),
        targetPaths = InstallationTargetPaths(
          skillsRoot = repoRoot.resolve("skills"),
          platformPacksRoot = repoRoot.resolve("platform-packs"),
          agentTargets = emptyList(),
        ),
        windowsSymlinkPreflight = WindowsSymlinkPreflight(
          state = WindowsSymlinkPreflightState.NOT_WINDOWS,
          decision = WindowsSymlinkDecision.NOT_REQUIRED,
        ),
      ),
    )
  }

  private fun assertTypeScriptInstallApplied(plan: InstallPlan) {
    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status, result.failures.joinToString { it.message })
    assertTrue(result.skills.all { skill -> skill.staging.status == InstallSkillStagingStatus.STAGED })
    val reviewSidecars = result.skills
      .single { skill -> skill.skillName == "bill-code-review" }
      .staging
      .renderedSidecarFiles
      .map { path -> path.fileName.toString() }
      .toSet()
    val expectedReviewSidecars = APPROVED_CODE_REVIEW_AREAS.map { area ->
      "bill-typescript-code-review-$area.md"
    }.toSet() + "bill-typescript-code-review.md"
    assertTrue(reviewSidecars.containsAll(expectedReviewSidecars))
    val checkSidecars = result.skills
      .single { skill -> skill.skillName == "bill-code-check" }
      .staging
      .renderedSidecarFiles
      .map { path -> path.fileName.toString() }
      .toSet()
    assertContains(checkSidecars, "bill-typescript-code-check.md")
    val linkedNativeAgents = result.nativeAgents
      .filter { nativeAgent -> nativeAgent.status == NativeAgentApplyStatus.LINKED }
      .mapNotNull { nativeAgent -> nativeAgent.path?.fileName?.toString() }
      .toSet()
    expectedReviewSidecars.map { sidecar -> sidecar.removeSuffix(".md") }.forEach { agentName ->
      assertTrue(linkedNativeAgents.any { artifact -> agentName in artifact }, "Missing native agent $agentName")
    }
  }

  private fun copyDirectory(source: Path, target: Path) {
    Files.walk(source).use { stream ->
      stream.forEach { path ->
        val destination = target.resolve(source.relativize(path))
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination)
        } else {
          Files.createDirectories(destination.parent)
          Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
        }
      }
    }
  }
}

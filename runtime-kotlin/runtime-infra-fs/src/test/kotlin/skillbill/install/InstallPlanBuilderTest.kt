@file:Suppress("LargeClass")

package skillbill.install

import skillbill.error.ContractVersionMismatchError
import skillbill.error.InvalidInstallPlanSchemaError
import skillbill.error.InvalidReviewSkillStructureError
import skillbill.error.MissingContentFileError
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.install.runtime.InstallOperations
import skillbill.install.staging.applicablePointers
import skillbill.install.staging.authoredFilesFor
import skillbill.install.staging.computeInstallContentHash
import skillbill.install.staging.generatedSupportPointersFor
import skillbill.testing.seedConformingPlatformPack
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallPlanBuilderTest {
  private val tempDirs = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    tempDirs.reversed().forEach { dir ->
      if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
        Files.walk(dir).use { stream ->
          stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
      }
    }
  }

  @Test
  fun `plan includes base skills and dynamically selected platform pack skills`() {
    val fixture = setupPlanFixture()

    val plan = InstallOperations.planInstall(
      fixture.request(
        platformPackSelection = PlatformPackSelection(
          mode = PlatformPackSelectionMode.SELECTED,
          selectedSlugs = setOf("kotlin"),
        ),
      ),
    )

    val skillsByName = plan.skills.associateBy { skill -> skill.name }
    assertEquals(
      listOf("kmp", "kotlin"),
      plan.discoveredPlatformPacks.map { pack -> pack.slug },
    )
    assertEquals(listOf("kotlin"), plan.selectedPlatformSlugs)
    assertEquals(InstallPlanSkillKind.BASE, skillsByName.getValue("bill-code-review").kind)
    assertEquals(InstallPlanSkillKind.BASE, skillsByName.getValue("bill-code-check").kind)
    assertEquals(InstallPlanSkillKind.BASE, skillsByName.getValue("bill-update-check").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-kotlin-code-review").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-kotlin-code-review-architecture").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-kotlin-code-review-testing").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-kotlin-code-check").kind)
    assertFalse(skillsByName.containsKey("bill-kmp-code-review"))
    assertFalse(skillsByName.containsKey("bill-kmp-code-review-architecture"))
  }

  @Test
  fun `selected platform planning is driven by newly discovered pack manifests with base skills included`() {
    val fixture = setupPlanFixture()
    seedPlatformPack(fixture.repoRoot, slug = "python", areaNames = listOf("security"))

    val plan = InstallOperations.planInstall(
      fixture.request(
        platformPackSelection = PlatformPackSelection(
          mode = PlatformPackSelectionMode.SELECTED,
          selectedSlugs = setOf("python"),
        ),
      ),
    )

    assertEquals(listOf("kmp", "kotlin", "python"), plan.discoveredPlatformPacks.map { pack -> pack.slug })
    assertEquals(listOf("python"), plan.selectedPlatformSlugs)
    val skillsByName = plan.skills.associateBy { skill -> skill.name }
    assertEquals(InstallPlanSkillKind.BASE, skillsByName.getValue("bill-code-review").kind)
    assertEquals(InstallPlanSkillKind.BASE, skillsByName.getValue("bill-code-check").kind)
    assertEquals(InstallPlanSkillKind.BASE, skillsByName.getValue("bill-update-check").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-python-code-review").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-python-code-review-security").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-python-code-check").kind)
    assertFalse(skillsByName.containsKey("bill-kotlin-code-review"))
    assertFalse(skillsByName.containsKey("bill-kmp-code-review"))
  }

  @Test
  fun `selected platform planning rejects malformed specialist structure`() {
    val fixture = setupPlanFixture()
    seedPlatformPack(fixture.repoRoot, slug = "invalid-review-shape")
    Files.writeString(
      fixture.repoRoot.resolve(
        "platform-packs/invalid-review-shape/code-review/" +
          "bill-invalid-review-shape-code-review-architecture/content.md",
      ),
      """
      |---
      |name: bill-invalid-review-shape-code-review-architecture
      |description: Malformed architecture specialist fixture.
      |internal-for: bill-code-review
      |---
      |
      |# Malformed Architecture Specialist
      |
      |## Focus
      |
      |Missing the governed specialist skeleton.
      |
      """.trimMargin(),
    )

    val error = assertFailsWith<InvalidReviewSkillStructureError> {
      InstallOperations.planInstall(
        fixture.request(
          platformPackSelection = PlatformPackSelection(
            mode = PlatformPackSelectionMode.SELECTED,
            selectedSlugs = setOf("invalid-review-shape"),
          ),
        ),
      )
    }

    assertContains(error.message.orEmpty(), "specialist H2 sequence")
  }

  @Test
  fun `manual agent selection resolves target paths and MCP intent without executing registration`() {
    val fixture = setupPlanFixture()
    val claudeTarget = fixture.home.resolve("manual-claude")
    val beforeHome = snapshotTree(fixture.home)

    val plan = InstallOperations.planInstall(
      fixture.request(
        agentSelection = InstallAgentSelection(
          mode = InstallAgentSelectionMode.MANUAL,
          manualAgents = setOf(InstallAgent.CLAUDE, InstallAgent.CODEX),
        ),
        targetPaths = fixture.targetPaths(
          agentTargets = listOf(
            InstallAgentTarget(
              agent = InstallAgent.CLAUDE,
              path = claudeTarget,
              source = InstallAgentTargetSource.MANUAL,
            ),
          ),
        ),
      ),
    )

    assertEquals(listOf(InstallAgent.CLAUDE, InstallAgent.CODEX), plan.agents.map { target -> target.agent })
    assertEquals(claudeTarget, plan.agents.first { target -> target.agent == InstallAgent.CLAUDE }.path)
    assertEquals(fixture.home.resolve(".agents/skills"), plan.agents.first { it.agent == InstallAgent.CODEX }.path)
    assertEquals(listOf(InstallAgent.CLAUDE, InstallAgent.CODEX), plan.mcpRegistrationIntent.agents)
    assertEquals(fixture.runtimeMcpBin, plan.mcpRegistrationIntent.runtimeMcpBin)
    assertTrue(plan.mcpRegistrationIntent.register)
    assertEquals(beforeHome, snapshotTree(fixture.home), "planning must not mutate the home tree")
  }

  @Test
  fun `detection derived agent selection uses existing detection without unsupported agents`() {
    val fixture = setupPlanFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))

    val plan = InstallOperations.planInstall(
      fixture.request(
        agentSelection = InstallAgentSelection(mode = InstallAgentSelectionMode.DETECTED),
      ),
    )

    assertEquals(listOf(InstallAgent.CODEX), plan.agents.map { target -> target.agent })
    assertEquals(listOf(InstallAgentTargetSource.DETECTED), plan.agents.map { target -> target.source })
    assertEquals(fixture.home.resolve(".codex/skills"), plan.agents.single().path)
  }

  @Test
  fun `detection derived agent selection preserves caller supplied detected targets`() {
    val fixture = setupPlanFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val detectedTarget = InstallAgentTarget(
      agent = InstallAgent.CLAUDE,
      path = fixture.home.resolve("detected-claude"),
      source = InstallAgentTargetSource.MANUAL,
    )

    val plan = InstallOperations.planInstall(
      fixture.request(
        agentSelection = InstallAgentSelection(
          mode = InstallAgentSelectionMode.DETECTED,
          detectedTargets = listOf(detectedTarget),
        ),
      ),
    )

    assertEquals(listOf(InstallAgent.CLAUDE), plan.agents.map { target -> target.agent })
    assertEquals(listOf(InstallAgentTargetSource.DETECTED), plan.agents.map { target -> target.source })
    assertEquals(fixture.home.resolve("detected-claude"), plan.agents.single().path)
    assertEquals(1, plan.agents.size)
  }

  @Test
  fun `all platform selection selects every discovered pack`() {
    val fixture = setupPlanFixture()

    val plan = InstallOperations.planInstall(
      fixture.request(
        platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.ALL),
      ),
    )

    assertEquals(listOf("kmp", "kotlin"), plan.selectedPlatformSlugs)
    assertTrue(plan.skills.any { skill -> skill.name == "bill-kmp-code-review" })
    assertTrue(plan.skills.any { skill -> skill.name == "bill-kmp-code-review-architecture" })
    assertTrue(plan.skills.any { skill -> skill.name == "bill-kmp-code-review-testing" })
    assertTrue(plan.skills.any { skill -> skill.name == "bill-kotlin-code-review" })
    assertTrue(plan.skills.any { skill -> skill.name == "bill-kotlin-code-review-architecture" })
    assertTrue(plan.skills.any { skill -> skill.name == "bill-kotlin-code-review-testing" })
  }

  @Test
  fun `unknown selected platform slugs fail during planning`() {
    val fixture = setupPlanFixture()

    val error = assertFailsWith<IllegalArgumentException> {
      InstallOperations.planInstall(
        fixture.request(
          platformPackSelection = PlatformPackSelection(
            mode = PlatformPackSelectionMode.SELECTED,
            selectedSlugs = setOf("swift"),
          ),
        ),
      )
    }

    assertContains(error.message.orEmpty(), "Unknown platform pack selection: swift")
    assertContains(error.message.orEmpty(), "Discovered platform packs: kmp, kotlin")
  }

  @Test
  fun `platform pack discovery validates selected pack contract and content`() {
    val badVersion = setupPlanFixture()
    val badVersionManifest = badVersion.repoRoot.resolve("platform-packs/kotlin/platform.yaml")
    Files.writeString(
      badVersionManifest,
      Files.readString(badVersionManifest).replace("contract_version: \"1.2\"", "contract_version: \"9.9\""),
    )

    val versionError = assertFailsWith<ContractVersionMismatchError> {
      InstallOperations.planInstall(
        badVersion.request(
          platformPackSelection = PlatformPackSelection(
            mode = PlatformPackSelectionMode.SELECTED,
            selectedSlugs = setOf("kotlin"),
          ),
        ),
      )
    }
    assertContains(versionError.message.orEmpty(), "contract_version '9.9'")

    val missingContent = setupPlanFixture()
    Files.delete(
      missingContent.repoRoot
        .resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review-architecture/content.md"),
    )

    val contentError = assertFailsWith<MissingContentFileError> {
      InstallOperations.planInstall(
        missingContent.request(
          platformPackSelection = PlatformPackSelection(
            mode = PlatformPackSelectionMode.SELECTED,
            selectedSlugs = setOf("kotlin"),
          ),
        ),
      )
    }
    assertContains(contentError.message.orEmpty(), "bill-kotlin-code-review-architecture/content.md")
  }

  @Test
  fun `selected platform declared content files cannot escape owning pack root`() {
    val fixture = setupPlanFixture()
    val outsideSkillDir = fixture.repoRoot.resolve("outside/bill-escaped-code-review")
    Files.createDirectories(outsideSkillDir)
    Files.writeString(outsideSkillDir.resolve("content.md"), content("bill-escaped-code-review"))
    val manifest = fixture.repoRoot.resolve("platform-packs/kotlin/platform.yaml")
    Files.writeString(
      manifest,
      Files.readString(manifest).replace(
        "baseline: \"code-review/bill-kotlin-code-review/content.md\"",
        "baseline: \"../../outside/bill-escaped-code-review/content.md\"",
      ),
    )

    val error = assertFailsWith<IllegalArgumentException> {
      InstallOperations.planInstall(
        fixture.request(
          platformPackSelection = PlatformPackSelection(
            mode = PlatformPackSelectionMode.SELECTED,
            selectedSlugs = setOf("kotlin"),
          ),
        ),
      )
    }

    assertContains(error.message.orEmpty(), "Platform pack 'kotlin' declared content file")
    assertContains(error.message.orEmpty(), "escapes packRoot")
  }

  @Test
  fun `duplicate planned skill names fail before staging intent is produced`() {
    val fixture = setupPlanFixture()
    seedPlatformPack(
      fixture.repoRoot,
      slug = "duplicate",
      qualityCheckName = "bill-code-review",
      areaNames = emptyList(),
    )

    val error = assertFailsWith<IllegalArgumentException> {
      InstallOperations.planInstall(
        fixture.request(
          platformPackSelection = PlatformPackSelection(
            mode = PlatformPackSelectionMode.SELECTED,
            selectedSlugs = setOf("duplicate"),
          ),
        ),
      )
    }

    assertContains(error.message.orEmpty(), "duplicate skill name")
    assertContains(error.message.orEmpty(), "bill-code-review")
  }

  @Test
  fun `duplicate platform manifest slots pointing to one skill directory fail during planning`() {
    val fixture = setupPlanFixture()
    seedPlatformPack(
      fixture.repoRoot,
      slug = "collapsed",
      areaNames = emptyList(),
    )
    val manifest = fixture.repoRoot.resolve("platform-packs/collapsed/platform.yaml")
    Files.writeString(
      manifest,
      Files.readString(manifest).replace(
        "declared_quality_check_file: \"quality-check/bill-collapsed-code-check/content.md\"",
        "declared_quality_check_file: \"code-review/bill-collapsed-code-review/content.md\"",
      ),
    )
    val collapsedContent = fixture.repoRoot
      .resolve("platform-packs/collapsed/code-review/bill-collapsed-code-review/content.md")
    Files.writeString(
      collapsedContent,
      Files.readString(collapsedContent).replace(
        "description: Test skill.\n",
        "description: Test skill.\ninternal-for: bill-code-check\n",
      ),
    )

    val error = assertFailsWith<IllegalArgumentException> {
      InstallOperations.planInstall(
        fixture.request(
          platformPackSelection = PlatformPackSelection(
            mode = PlatformPackSelectionMode.SELECTED,
            selectedSlugs = setOf("collapsed"),
          ),
        ),
      )
    }

    assertContains(error.message.orEmpty(), "duplicate skill name")
    assertContains(error.message.orEmpty(), "bill-collapsed-code-review")
  }

  @Test
  fun `missing base skills root fails instead of producing a plan without base skills`() {
    val fixture = setupPlanFixture()

    val error = assertFailsWith<java.io.FileNotFoundException> {
      InstallOperations.planInstall(
        fixture.request(
          targetPaths = fixture.targetPaths().copy(skillsRoot = fixture.repoRoot.resolve("missing-skills")),
        ),
      )
    }

    assertContains(error.message.orEmpty(), "Base skills root")
  }

  @Test
  fun `base skill directories without content fail even when another base skill is valid`() {
    val fixture = setupPlanFixture()
    Files.delete(fixture.repoRoot.resolve("skills/bill-code-review/content.md"))

    val error = assertFailsWith<IllegalArgumentException> {
      InstallOperations.planInstall(fixture.request())
    }

    assertContains(error.message.orEmpty(), "without content.md")
    assertContains(error.message.orEmpty(), "bill-code-review")
  }

  @Test
  fun `existing base skills root with no valid bill skills fails during planning`() {
    val fixture = setupPlanFixture()
    val emptySkillsRoot = fixture.repoRoot.resolve("empty-skills")
    Files.createDirectories(emptySkillsRoot.resolve("notes"))

    val error = assertFailsWith<IllegalArgumentException> {
      InstallOperations.planInstall(
        fixture.request(
          targetPaths = fixture.targetPaths().copy(skillsRoot = emptySkillsRoot),
        ),
      )
    }

    assertContains(error.message.orEmpty(), "does not contain any bill-* skills with content.md")
  }

  @Test
  fun `base support pointers use requested skills root rather than repo root skills`() {
    val fixture = setupPlanFixture()
    val packagedSkillsRoot = fixture.repoRoot.resolve("packaged-skills")
    seedBaseSkillAt(packagedSkillsRoot, "bill-code-review")
    seedSupportTarget(fixture.repoRoot, "orchestration/shell-content-contract/shell-ceremony.md")
    seedSkillClass(fixture.repoRoot, "bill-code-review", listOf("shell-ceremony"))

    val plan = InstallOperations.planInstall(
      fixture.request(
        targetPaths = fixture.targetPaths().copy(skillsRoot = packagedSkillsRoot),
      ),
    )

    val sourceDir = packagedSkillsRoot.resolve("bill-code-review").toAbsolutePath().normalize()
    val supportPointers = generatedSupportPointersFor(
      repoRoot = fixture.repoRoot,
      sourceSkillDir = sourceDir,
      skillName = "bill-code-review",
      skillsRoot = packagedSkillsRoot,
    )
    val authored = authoredFilesFor(sourceDir, applicablePointers(fixture.repoRoot, sourceDir), supportPointers)
    val expectedHash = computeInstallContentHash(
      sourceSkillDir = sourceDir,
      authored = authored,
      applicablePointers = emptyList(),
      generatedSupportPointers = supportPointers,
    )

    assertEquals(listOf("shell-ceremony.md"), supportPointers.map { pointer -> pointer.name })
    assertEquals(
      expectedHash,
      plan.staging.skillPaths.single { path -> path.skillName == "bill-code-review" }.contentHash,
    )
  }

  @Test
  fun `planning fails when selected platform pointer target is missing`() {
    val fixture = setupPlanFixture()
    seedPlatformPack(
      fixture.repoRoot,
      slug = "pointer-missing",
      areaNames = emptyList(),
      pointerTarget = "orchestration/missing/PLAYBOOK.md",
    )

    val error = assertFailsWith<IllegalArgumentException> {
      InstallOperations.planInstall(
        fixture.request(
          platformPackSelection = PlatformPackSelection(
            mode = PlatformPackSelectionMode.SELECTED,
            selectedSlugs = setOf("pointer-missing"),
          ),
        ),
      )
    }

    assertContains(error.message.orEmpty(), "review-orchestrator.md")
    assertContains(error.message.orEmpty(), "does not exist")
  }

  @Test
  fun `planning fails when selected platform pointer target escapes through symlinked parent`() {
    val fixture = setupPlanFixture()
    val outsideRoot = Files.createTempDirectory("skillbill-install-plan-pointer-target").also(tempDirs::add)
    Files.writeString(outsideRoot.resolve("PLAYBOOK.md"), "# Outside target\n")
    Files.createSymbolicLink(fixture.repoRoot.resolve("orchestration"), outsideRoot)
    seedPlatformPack(
      fixture.repoRoot,
      slug = "pointer-parent-symlink",
      areaNames = emptyList(),
      pointerTarget = "orchestration/PLAYBOOK.md",
    )

    val error = assertFailsWith<IllegalArgumentException> {
      InstallOperations.planInstall(
        fixture.request(
          platformPackSelection = PlatformPackSelection(
            mode = PlatformPackSelectionMode.SELECTED,
            selectedSlugs = setOf("pointer-parent-symlink"),
          ),
        ),
      )
    }

    assertContains(error.message.orEmpty(), "review-orchestrator.md")
    assertContains(error.message.orEmpty(), "escapes repoRoot")
  }

  @Test
  fun `planning fails when generated support pointer target is missing`() {
    val fixture = setupPlanFixture()
    seedSkillClass(fixture.repoRoot, "bill-code-review", listOf("shell-ceremony"))

    val error = assertFailsWith<IllegalArgumentException> {
      InstallOperations.planInstall(fixture.request())
    }

    assertContains(error.message.orEmpty(), "Supporting pointer 'shell-ceremony.md'")
    assertContains(error.message.orEmpty(), "does not exist")
  }

  @Test
  fun `staging runtime telemetry and windows intent are represented without applying`() {
    val fixture = setupPlanFixture()
    val before = snapshotTree(fixture.repoRoot)

    val plan = InstallOperations.planInstall(
      fixture.request(
        telemetryLevel = InstallTelemetryLevel.FULL,
        windowsSymlinkPreflight = WindowsSymlinkPreflight(
          state = WindowsSymlinkPreflightState.DECISION_REQUIRED,
          decision = WindowsSymlinkDecision.REQUIRE_USER_ACTION,
          message = "Windows requires elevation or Developer Mode before symlink install.",
        ),
      ),
    )

    assertEquals(fixture.home.resolve(".skill-bill/installed-skills"), plan.staging.root)
    assertTrue(plan.staging.skillPaths.isNotEmpty())
    assertTrue(plan.staging.skillPaths.all { path -> path.stagingDir.startsWith(plan.staging.root) })
    assertEquals(InstallTelemetryLevel.FULL, plan.telemetryLevel)
    assertEquals(fixture.runtimeInstallRoot, plan.runtimeDistributionInputs.runtimeInstallRoot)
    assertEquals(WindowsSymlinkPreflightState.DECISION_REQUIRED, plan.windowsSymlinkPreflight.state)
    assertEquals(WindowsSymlinkDecision.REQUIRE_USER_ACTION, plan.windowsSymlinkPreflight.decision)
    assertEquals(
      "Windows requires elevation or Developer Mode before symlink install.",
      plan.windowsSymlinkPreflight.message,
    )
    assertEquals(before, snapshotTree(fixture.repoRoot), "planning must not write generated artifacts into source")
  }

  @Test
  fun `builder seam still validates install plan wire map with typed schema error`() {
    val fixture = setupPlanFixture()

    val error = assertFailsWith<InvalidInstallPlanSchemaError> {
      InstallOperations.planInstall(
        fixture.request().copy(
          mcpRegistrationChoice = McpRegistrationChoice(register = true, runtimeMcpBin = Path.of("")),
        ),
      )
    }

    assertContains(error.message.orEmpty(), "mcp_registration.runtime_mcp_bin")
  }

  private fun setupPlanFixture(): PlanFixture {
    val repoRoot = Files.createTempDirectory("skillbill-install-plan-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-install-plan-home").also(tempDirs::add)
    seedBaseSkill(repoRoot, "bill-code-review")
    seedBaseSkill(repoRoot, "bill-code-check")
    seedBaseSkill(repoRoot, "bill-update-check")
    seedPlatformPack(repoRoot, "kotlin", areaNames = listOf("architecture", "testing"))
    seedPlatformPack(repoRoot, "kmp", areaNames = listOf("architecture", "testing"))
    return PlanFixture(repoRoot = repoRoot, home = home)
  }

  private fun seedBaseSkill(repoRoot: Path, skillName: String) {
    seedBaseSkillAt(repoRoot.resolve("skills"), skillName)
  }

  private fun seedBaseSkillAt(skillsRoot: Path, skillName: String) {
    val skillDir = skillsRoot.resolve(skillName)
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      |---
      |name: $skillName
      |description: Test skill.
      |---
      |
      |## Execution
      |
      |Test body.
      |
      """.trimMargin(),
    )
  }

  private fun seedPlatformPack(
    repoRoot: Path,
    slug: String,
    qualityCheckName: String = "bill-$slug-code-check",
    areaNames: List<String> = listOf("architecture"),
    pointerTarget: String? = null,
  ) {
    seedConformingPlatformPack(
      repoRoot = repoRoot,
      slug = slug,
      qualityCheckName = qualityCheckName,
      areaNames = areaNames.ifEmpty { listOf("architecture") },
      baselinePointerTarget = pointerTarget,
    )
  }

  private fun content(name: String, internalFor: String? = null): String = buildString {
    appendLine("---")
    appendLine("name: $name")
    appendLine("description: Test skill.")
    internalFor?.let { parent -> appendLine("internal-for: $parent") }
    appendLine("---")
    appendLine()
    appendLine("# $name")
    appendLine()
    appendLine("Test body.")
  }

  private fun seedSkillClass(repoRoot: Path, skillName: String, pointers: List<String>) {
    val classRoot = repoRoot.resolve("orchestration/skill-classes")
    Files.createDirectories(classRoot)
    Files.writeString(
      classRoot.resolve("install-plan-test.yaml"),
      """
      |class: install-plan-test
      |contract_version: "1.2"
      |matchers:
      |  - exact: $skillName
      |pointers:
      |${pointers.joinToString("\n") { pointer -> "  - $pointer" }}
      |
      """.trimMargin(),
    )
  }

  private fun seedSupportTarget(repoRoot: Path, relativePath: String) {
    val target = repoRoot.resolve(relativePath)
    Files.createDirectories(target.parent)
    Files.writeString(target, "# Support target\n")
  }

  private fun snapshotTree(root: Path): Map<String, String> {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return emptyMap()
    }
    return Files.walk(root).use { stream ->
      stream
        .sorted()
        .toList()
        .associate { path ->
          val relative = root.relativize(path)
            .toString()
            .replace(java.io.File.separatorChar, '/')
            .ifEmpty { "." }
          val value = when {
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> "<DIR>"
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> Files.readString(path)
            else -> "<OTHER>"
          }
          relative to value
        }
    }
  }

  private data class PlanFixture(
    val repoRoot: Path,
    val home: Path,
  ) {
    val runtimeInstallRoot: Path = home.resolve(".skill-bill/runtime")
    val runtimeMcpBin: Path = runtimeInstallRoot.resolve("runtime-mcp/bin/runtime-mcp")

    fun targetPaths(agentTargets: List<InstallAgentTarget> = emptyList()): InstallationTargetPaths =
      InstallationTargetPaths(
        skillsRoot = repoRoot.resolve("skills"),
        platformPacksRoot = repoRoot.resolve("platform-packs"),
        agentTargets = agentTargets,
      )

    fun request(
      agentSelection: InstallAgentSelection = InstallAgentSelection(
        mode = InstallAgentSelectionMode.MANUAL,
        manualAgents = setOf(InstallAgent.CODEX),
      ),
      platformPackSelection: PlatformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.NONE),
      telemetryLevel: InstallTelemetryLevel = InstallTelemetryLevel.ANONYMOUS,
      targetPaths: InstallationTargetPaths = targetPaths(),
      windowsSymlinkPreflight: WindowsSymlinkPreflight = WindowsSymlinkPreflight(
        state = WindowsSymlinkPreflightState.NOT_WINDOWS,
        decision = WindowsSymlinkDecision.NOT_REQUIRED,
      ),
    ): InstallPlanRequest = InstallPlanRequest(
      repoRoot = repoRoot,
      home = home,
      agentSelection = agentSelection,
      platformPackSelection = platformPackSelection,
      telemetryLevel = telemetryLevel,
      mcpRegistrationChoice = McpRegistrationChoice(register = true, runtimeMcpBin = runtimeMcpBin),
      runtimeDistributionInputs = RuntimeDistributionInputs(runtimeInstallRoot = runtimeInstallRoot),
      targetPaths = targetPaths,
      windowsSymlinkPreflight = windowsSymlinkPreflight,
    )
  }
}

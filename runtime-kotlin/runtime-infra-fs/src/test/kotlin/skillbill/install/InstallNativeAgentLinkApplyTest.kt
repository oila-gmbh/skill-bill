package skillbill.install

import org.junit.jupiter.api.Assumptions
import skillbill.error.InvalidNativeAgentLinkInventorySchemaError
import skillbill.error.MissingInstalledNativeAgentError
import skillbill.infrastructure.fs.FileSystemReviewNativeAgentPreflight
import skillbill.install.apply.currentNativeAgentApplyCacheRoot
import skillbill.install.model.AgentTarget
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentLinkStatus
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.McpRegistrationApplyStatus
import skillbill.install.model.NativeAgentApplyStatus
import skillbill.install.model.NativeAgentProviderId
import skillbill.install.nativeagent.InstallNativeAgentResult
import skillbill.install.nativeagent.NativeAgentLinkInventory
import skillbill.install.nativeagent.installNativeAgentFile
import skillbill.install.runtime.InstallOperations
import skillbill.install.support.createNewSymlinkWithGuidance
import skillbill.model.EnvironmentContext
import skillbill.nativeagent.rendering.NativeAgentOperations
import skillbill.nativeagent.rendering.NativeAgentProvider
import skillbill.ports.review.model.ReviewNativeAgentPreflightRequest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstallNativeAgentLinkApplyTest : InstallApplyTestSupport() {
  @Test
  fun `inventory rejects a logical name whose installed filename identifies another worker`() {
    val fixture = setupApplyFixture()
    val cacheRoot = currentNativeAgentApplyCacheRoot(
      fixture.home,
      fixture.repoRoot.resolve("platform-packs"),
      fixture.repoRoot.resolve("skills"),
    )
    val inventory = fixture.home.resolve(".skill-bill/native-agent-link-inventory.json")
    Files.createDirectories(inventory.parent)
    Files.writeString(
      inventory,
      inventoryJson(
        logicalName = "bill-code-review-worker",
        installedPath = fixture.home.resolve(".codex/agents/bill-other-worker.toml"),
        cacheTargetPath = cacheRoot.resolve("codex-agents/bill-code-review-worker.toml"),
        sourceRoot = fixture.repoRoot,
      ),
    )

    assertFailsWith<InvalidNativeAgentLinkInventorySchemaError> {
      NativeAgentLinkInventory.read(fixture.home, listOf(cacheRoot))
    }
  }

  @Test
  fun `preflight rejects stale Codex inventory when provider root disappeared`() {
    val fixture = setupApplyFixture()
    val cacheRoot = currentNativeAgentApplyCacheRoot(
      fixture.home,
      fixture.repoRoot.resolve("platform-packs"),
      fixture.repoRoot.resolve("skills"),
    )
    val inventory = fixture.home.resolve(".skill-bill/native-agent-link-inventory.json")
    Files.createDirectories(inventory.parent)
    Files.writeString(
      inventory,
      inventoryJson(
        logicalName = "bill-code-review-worker",
        installedPath = fixture.home.resolve(".agents/agents/bill-code-review-worker.toml"),
        cacheTargetPath = cacheRoot.resolve("codex-agents/bill-code-review-worker.toml"),
        sourceRoot = fixture.repoRoot,
      ),
    )

    val error = assertFailsWith<MissingInstalledNativeAgentError> {
      FileSystemReviewNativeAgentPreflight(EnvironmentContext(userHome = fixture.home)).verify(
        ReviewNativeAgentPreflightRequest(
          repoRoot = fixture.repoRoot,
          agentIds = listOf("codex"),
          logicalNames = listOf("bill-code-review-worker"),
        ),
      )
    }

    assertTrue(error.message.orEmpty().contains("active provider directory is missing"))
    assertEquals("skill-bill install apply", error.repairCommand)
  }

  @Test
  fun `failed first reconciliation restores absent provider root cache metadata and inventory`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val providerDir = fixture.home.resolve(".codex/agents")
    val cacheRoot = currentNativeAgentApplyCacheRoot(
      fixture.home,
      fixture.repoRoot.resolve("platform-packs"),
      fixture.repoRoot.resolve("skills"),
    )
    val sentinel = cacheRoot.resolve("sentinel")
    Files.createDirectories(cacheRoot)
    Files.writeString(sentinel, "prior cache")
    val permissions = readPosixPermissionsOrSkip(sentinel) - PosixFilePermission.OWNER_EXECUTE
    Files.setPosixFilePermissions(sentinel, permissions)
    val inventory = fixture.home.resolve(".skill-bill/native-agent-link-inventory.json")
    Files.createDirectories(inventory.parent)
    val invalidInventory = "not-json"
    Files.writeString(inventory, invalidInventory)
    val plan = InstallOperations.planInstall(
      fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(InstallAgent.CODEX)),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertFalse(Files.exists(providerDir, LinkOption.NOFOLLOW_LINKS))
    assertEquals("prior cache", Files.readString(sentinel))
    assertEquals(permissions, Files.getPosixFilePermissions(sentinel))
    assertEquals(invalidInventory, Files.readString(inventory))
  }

  @Test
  fun `preflight accepts the current installed-skills native-agent generation`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan = InstallOperations.planInstall(
      fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(InstallAgent.CODEX)),
    )
    val result = InstallOperations.applyInstall(plan)
    assertEquals(InstallApplyStatus.SUCCESS, result.status)

    FileSystemReviewNativeAgentPreflight(EnvironmentContext(userHome = fixture.home)).verify(
      ReviewNativeAgentPreflightRequest(
        repoRoot = fixture.repoRoot,
        agentIds = listOf("codex"),
        logicalNames = listOf("bill-code-review-worker"),
      ),
    )
  }

  @Test
  fun `apply removes inventory-recorded dangling baseline orchestrator links`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val agentDir = fixture.home.resolve(".codex/agents")
    Files.createDirectories(agentDir)
    val managedRoot = fixture.home.resolve(".skill-bill/installed-skills/codex-agents")
    Files.createDirectories(managedRoot)
    val kotlinTarget = managedRoot.resolve("bill-kotlin-code-review.toml")
    val kmpTarget = managedRoot.resolve("bill-kmp-code-review.toml")
    val kotlinLink = agentDir.resolve(kotlinTarget.fileName)
    val kmpLink = agentDir.resolve(kmpTarget.fileName)
    createSymlinkOrSkip(kotlinLink, kotlinTarget)
    createSymlinkOrSkip(kmpLink, kmpTarget)
    val inventory = fixture.home.resolve(".skill-bill/native-agent-link-inventory.json")
    Files.createDirectories(inventory.parent)
    Files.writeString(
      inventory,
      """
      {"contract_version":"0.1","entries":[
        {"logical_name":"bill-kotlin-code-review","provider":"codex","installed_path":"$kotlinLink","cache_target_path":"$kotlinTarget","content_digest":"${"0".repeat(
        64,
      )}","source_root":"${fixture.repoRoot}"},
        {"logical_name":"bill-kmp-code-review","provider":"codex","installed_path":"$kmpLink","cache_target_path":"$kmpTarget","content_digest":"${"0".repeat(
        64,
      )}","source_root":"${fixture.repoRoot}"}
      ]}
      """.trimIndent(),
    )
    val plan = InstallOperations.planInstall(
      fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(InstallAgent.CODEX)),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertFalse(Files.exists(kotlinLink, LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(kmpLink, LinkOption.NOFOLLOW_LINKS))
  }

  @Test
  fun `selected all-agent apply distinguishes Copilot skill and MCP handling from native providers`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".claude"))
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.createDirectories(fixture.home.resolve(".config/opencode"))
    Files.createDirectories(fixture.home.resolve(".junie"))
    val sourceBefore = snapshotSource(fixture.repoRoot)
    val plan = InstallOperations.planInstall(
      fixture.request(
        selectedPlatforms = setOf("kotlin"),
        agents = allInstallAgents,
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertEquals(InstallAgent.entries.sortedBy(InstallAgent::id), result.mcpRegistrationIntent.agents)
    assertEquals(InstallAgent.entries.toSet(), result.mcpRegistrationOutcomes.map { outcome -> outcome.agent }.toSet())
    assertTrue(result.mcpRegistrationOutcomes.all { outcome -> outcome.status == McpRegistrationApplyStatus.SUCCESS })
    result.skills.forEach { skill ->
      assertEquals(
        InstallAgent.entries.toSet(),
        skill.links.map { link -> link.agent }.toSet(),
        "${skill.skillName} did not link to every selected skill target",
      )
      assertTrue(skill.links.all { link -> link.status == InstallAgentLinkStatus.CREATED })
      assertTrue(
        Files.isSymbolicLink(
          fixture.home.resolve("agent-skill-targets/copilot/${skill.skillName}"),
        ),
        "Copilot should receive the skill link surface",
      )
    }
    assertEquals(
      setOf(
        NativeAgentProviderId.CLAUDE,
        NativeAgentProviderId.CODEX,
        NativeAgentProviderId.OPENCODE,
        NativeAgentProviderId.JUNIE,
      ),
      result.nativeAgents
        .filter { native -> native.status == NativeAgentApplyStatus.LINKED }
        .map { native -> native.provider }
        .toSet(),
    )
    assertFalse(result.nativeAgents.any { native -> native.agent == InstallAgent.COPILOT })
    assertFalse(Files.exists(fixture.home.resolve(".copilot/agents"), LinkOption.NOFOLLOW_LINKS))
    assertSourceUnchanged(fixture.repoRoot, sourceBefore)
  }

  @Test
  fun `new symlink creation preserves destination that appears before move`() {
    val targetDir = Files.createTempDirectory("skillbill-new-link-target").also(tempDirs::add)
    val source = Files.createTempFile("skillbill-new-link-source", ".md").also(tempDirs::add)
    val linkPath = targetDir.resolve("bill-worker.md")
    Files.writeString(linkPath, "user owned")

    val failure = runCatching { createNewSymlinkWithGuidance(linkPath, source) }.exceptionOrNull()

    assertNotNull(failure, "new link creation should fail when destination exists")
    assertEquals("user owned", Files.readString(linkPath))
    assertFalse(Files.isSymbolicLink(linkPath), "user-owned file should not be replaced")
  }

  @Test
  fun `native agent install preserves unmanaged legacy symlink outside current roots`() {
    val targetDir = Files.createTempDirectory("skillbill-native-target").also(tempDirs::add)
    val managedRoot = Files.createTempDirectory("skillbill-native-managed-root").also(tempDirs::add)
    val otherRepo = Files.createTempDirectory("skillbill-native-other-repo").also(tempDirs::add)
    val newSource = managedRoot.resolve("bill-worker.md")
    val userSource = otherRepo.resolve("skills/codex/bill-worker.md")
    Files.createDirectories(userSource.parent)
    Files.writeString(newSource, "new")
    Files.writeString(userSource, "user")
    val linkPath = targetDir.resolve("bill-worker.md")
    createSymlinkOrSkip(linkPath, userSource)

    val result = installNativeAgentFile(
      source = newSource,
      agentTarget = AgentTarget("codex", targetDir),
      managedSourceRoots = listOf(managedRoot),
    )

    assertTrue(result is InstallNativeAgentResult.Skipped)
    assertEquals(userSource.toAbsolutePath().normalize(), readSymlinkTarget(linkPath))
  }

  @Test
  fun `native agent install preserves non agent symlink inside source roots`() {
    val targetDir = Files.createTempDirectory("skillbill-native-target").also(tempDirs::add)
    val cacheRoot = Files.createTempDirectory("skillbill-native-cache-root").also(tempDirs::add)
    val repoRoot = Files.createTempDirectory("skillbill-native-repo-root").also(tempDirs::add)
    val newSource = cacheRoot.resolve("bill-worker.md")
    val userSource = repoRoot.resolve("platform-packs/kotlin/README.md")
    Files.createDirectories(userSource.parent)
    Files.writeString(newSource, "new")
    Files.writeString(userSource, "user")
    val linkPath = targetDir.resolve("bill-worker.md")
    createSymlinkOrSkip(linkPath, userSource)

    val result = installNativeAgentFile(
      source = newSource,
      agentTarget = AgentTarget("codex", targetDir),
      managedSourceRoots = listOf(cacheRoot),
    )

    assertTrue(result is InstallNativeAgentResult.Skipped)
    assertEquals(userSource.toAbsolutePath().normalize(), readSymlinkTarget(linkPath))
  }

  @Test
  fun `apply replaces existing native agent links from legacy generated cache`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val targetDir = fixture.home.resolve(".codex/agents")
    Files.createDirectories(targetDir)
    val legacyRoot = NativeAgentOperations.installCacheRoot(
      home = fixture.home,
      platformPacksRoot = fixture.repoRoot.resolve("platform-packs"),
      skillsRoot = fixture.repoRoot.resolve("skills"),
    )
    val legacyFile = legacyRoot
      .resolve(NativeAgentProvider.Codex.directoryName)
      .resolve("bill-code-review-worker.${NativeAgentProvider.Codex.extension}")
    Files.createDirectories(legacyFile.parent)
    Files.writeString(legacyFile, "legacy")
    val linkPath = targetDir.resolve(legacyFile.fileName)
    createSymlinkOrSkip(linkPath, legacyFile)
    val plan = InstallOperations.planInstall(
      fixture.request(
        selectedPlatforms = setOf("kotlin"),
        agents = setOf(InstallAgent.CODEX),
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val newTarget = readSymlinkTarget(linkPath)
    assertTrue(newTarget.startsWith(fixture.home.resolve(".skill-bill/installed-skills")))
    assertFalse(newTarget.startsWith(legacyRoot))
  }

  @Test
  fun `replacement apply removes native agent links from deselected platforms`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val kmpPlan = InstallOperations.planInstall(
      fixture.request(
        selectedPlatforms = setOf("kmp"),
        agents = setOf(InstallAgent.CODEX),
      ),
    )
    val first = InstallOperations.applyInstall(kmpPlan)
    assertEquals(InstallApplyStatus.SUCCESS, first.status)
    val targetDir = fixture.home.resolve(".codex/agents")
    val baseNativeAgent = targetDir.resolve("bill-code-review-worker.toml")
    val kmpNativeAgent = targetDir.resolve("bill-kmp-code-review-worker.toml")
    assertTrue(Files.isSymbolicLink(baseNativeAgent))
    assertTrue(Files.isSymbolicLink(kmpNativeAgent))
    val legacyRoot = NativeAgentOperations.installCacheRoot(
      home = fixture.home,
      platformPacksRoot = fixture.repoRoot.resolve("platform-packs"),
      skillsRoot = fixture.repoRoot.resolve("skills"),
    )
    val legacyKmpNativeAgent = legacyRoot
      .resolve(NativeAgentProvider.Codex.directoryName)
      .resolve(kmpNativeAgent.fileName)
    Files.createDirectories(legacyKmpNativeAgent.parent)
    Files.writeString(legacyKmpNativeAgent, "legacy kmp")
    Files.delete(kmpNativeAgent)
    createSymlinkOrSkip(kmpNativeAgent, legacyKmpNativeAgent)
    assertEquals(legacyKmpNativeAgent.toAbsolutePath().normalize(), readSymlinkTarget(kmpNativeAgent))

    val baseOnlyReplacementPlan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
        replaceExistingSkillBillLinks = true,
      ),
    )
    val second = InstallOperations.applyInstall(baseOnlyReplacementPlan)

    assertEquals(InstallApplyStatus.SUCCESS, second.status)
    assertTrue(Files.isSymbolicLink(baseNativeAgent))
    assertFalse(Files.exists(kmpNativeAgent, LinkOption.NOFOLLOW_LINKS))
  }

  @Test
  fun `native agent replacement preserves existing link when replacement symlink creation fails`() {
    val targetDir = Files.createTempDirectory("skillbill-native-readonly-target").also(tempDirs::add)
    val managedRoot = Files.createTempDirectory("skillbill-native-managed-root").also(tempDirs::add)
    val newRoot = Files.createTempDirectory("skillbill-native-new-root").also(tempDirs::add)
    val oldSource = managedRoot.resolve("bill-worker.md")
    val newSource = newRoot.resolve("bill-worker.md")
    Files.writeString(oldSource, "old")
    Files.writeString(newSource, "new")
    val linkPath = targetDir.resolve("bill-worker.md")
    createSymlinkOrSkip(linkPath, oldSource)
    val originalPermissions = readPosixPermissionsOrSkip(targetDir)
    try {
      Files.setPosixFilePermissions(
        targetDir,
        originalPermissions - PosixFilePermission.OWNER_WRITE -
          PosixFilePermission.GROUP_WRITE -
          PosixFilePermission.OTHERS_WRITE,
      )
      val probe = targetDir.resolve("probe")
      val canStillCreateSymlink = runCatching {
        Files.createSymbolicLink(probe, newSource)
        Files.deleteIfExists(probe)
      }.isSuccess
      Assumptions.assumeFalse(canStillCreateSymlink, "read-only directory still allows symlink creation")

      val failure = runCatching {
        installNativeAgentFile(
          source = newSource,
          agentTarget = AgentTarget("codex", targetDir),
          managedSourceRoots = listOf(managedRoot),
        )
      }.exceptionOrNull()

      assertNotNull(failure, "replacement should fail in read-only target dir")
      assertEquals(oldSource.toAbsolutePath().normalize(), readSymlinkTarget(linkPath))
    } finally {
      Files.setPosixFilePermissions(targetDir, originalPermissions)
    }
  }

  private fun inventoryJson(logicalName: String, installedPath: Path, cacheTargetPath: Path, sourceRoot: Path) = """
    {"contract_version":"0.1","entries":[
      {"logical_name":"$logicalName","provider":"codex","installed_path":"$installedPath","cache_target_path":"$cacheTargetPath","content_digest":"${"0".repeat(
    64,
  )}","source_root":"$sourceRoot"}
    ]}
  """.trimIndent()
}

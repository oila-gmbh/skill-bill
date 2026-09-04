package skillbill.install

import skillbill.error.InvalidNativeAgentLinkInventorySchemaError
import skillbill.error.MissingInstalledNativeAgentError
import skillbill.infrastructure.fs.FileSystemReviewNativeAgentPreflight
import skillbill.install.apply.currentNativeAgentApplyCacheRoot
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.NativeAgentApplyStatus
import skillbill.install.model.NativeAgentProviderId
import skillbill.install.nativeagent.NativeAgentLinkInventory
import skillbill.nativeagent.rendering.NativeAgentProvider
import skillbill.ports.review.model.ReviewNativeAgentPreflightRequest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallNativeAgentLinkApplyCodexTest : InstallNativeAgentLinkApplyTestSupport() {
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
  fun `inventory rejects an entry object with a duplicated field key`() {
    val fixture = setupApplyFixture()
    val cacheRoot = currentNativeAgentApplyCacheRoot(
      fixture.home,
      fixture.repoRoot.resolve("platform-packs"),
      fixture.repoRoot.resolve("skills"),
    )
    val inventory = fixture.home.resolve(".skill-bill/native-agent-link-inventory.json")
    Files.createDirectories(inventory.parent)
    val installedPath = fixture.home.resolve(".codex/agents/bill-code-review-worker.toml")
    val cacheTargetPath = cacheRoot.resolve("codex-agents/bill-code-review-worker.toml")
    Files.writeString(
      inventory,
      """
      {"contract_version":"0.2","entries":[
        {"logical_name":"bill-code-review-worker","provider":"codex","installed_path":"$installedPath",
         "cache_target_path":"$cacheTargetPath","content_digest":"${"0".repeat(64)}",
         "content_digest":"${"1".repeat(64)}","source_root":"${fixture.repoRoot}"}
      ]}
      """.trimIndent(),
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
      FileSystemReviewNativeAgentPreflight(preflightContext(fixture.home)).verify(
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
    val plan = planInstallForTest(
      fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(InstallAgent.CODEX)),
    )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertFalse(Files.exists(providerDir, LinkOption.NOFOLLOW_LINKS))
    assertEquals("prior cache", Files.readString(sentinel))
    assertEquals(permissions, Files.getPosixFilePermissions(sentinel))
    assertEquals(invalidInventory, Files.readString(inventory))
  }

  @Test
  fun `failed first reconciliation removes every transaction created ancestor`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val inventory = fixture.home.resolve(".skill-bill/native-agent-link-inventory.json")
    Files.createDirectories(inventory.parent)
    Files.writeString(inventory, "not-json")
    val nativeAgentCache = currentNativeAgentApplyCacheRoot(
      fixture.home,
      fixture.repoRoot.resolve("platform-packs"),
      fixture.repoRoot.resolve("skills"),
    )
    val providerAgents = fixture.home.resolve(".codex/agents")
    val plan = planInstallForTest(
      fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(InstallAgent.CODEX)),
    )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertFalse(Files.exists(nativeAgentCache, LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(providerAgents, LinkOption.NOFOLLOW_LINKS))
    assertEquals("not-json", Files.readString(inventory))
  }

  @Test
  fun `preflight accepts the current installed-skills native-agent generation`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan = planInstallForTest(
      fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(InstallAgent.CODEX)),
    )
    val result = applyInstallForTest(plan)
    assertEquals(InstallApplyStatus.SUCCESS, result.status)

    FileSystemReviewNativeAgentPreflight(preflightContext(fixture.home)).verify(
      ReviewNativeAgentPreflightRequest(
        repoRoot = fixture.repoRoot,
        agentIds = listOf("codex"),
        logicalNames = listOf("bill-code-review-worker"),
      ),
    )
  }

  @Test
  fun `cursor apply links workers, inventories them, is idempotent, and preserves user files`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".cursor"))
    val agentDir = fixture.home.resolve(".cursor/agents")
    Files.createDirectories(agentDir)
    val userFile = agentDir.resolve("user-owned.md")
    Files.writeString(userFile, "user cursor file\n")
    val plan = planInstallForTest(
      fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(InstallAgent.CURSOR)),
    )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val linked = result.nativeAgents.filter { native -> native.status == NativeAgentApplyStatus.LINKED }
    assertTrue(linked.isNotEmpty(), "cursor apply linked nothing: ${result.nativeAgents}")
    assertEquals(setOf(NativeAgentProviderId.CURSOR), linked.map { native -> native.provider }.toSet())
    assertEquals("user cursor file\n", Files.readString(userFile))

    val currentRoot = currentNativeAgentApplyCacheRoot(
      fixture.home,
      fixture.repoRoot.resolve("platform-packs"),
      fixture.repoRoot.resolve("skills"),
    )
    val entries = NativeAgentLinkInventory.read(fixture.home, listOf(currentRoot), fixture.repoRoot)
      .filter { entry -> entry.provider == "cursor" }
    assertTrue(entries.isNotEmpty(), "cursor links were not inventoried")
    entries.forEach { entry ->
      assertEquals(agentDir, entry.installedPath.parent)
      assertEquals(
        NativeAgentProvider.Cursor.cacheArtifactPath(currentRoot, entry.logicalName),
        entry.cacheTargetPath,
      )
      assertTrue(entry.contentDigest.matches(Regex("[0-9a-f]{64}")))
      assertEquals(fixture.repoRoot.toAbsolutePath().normalize(), entry.sourceRoot)
      assertTrue(Files.isSymbolicLink(entry.installedPath))
    }

    val repeat = applyInstallForTest(
      planInstallForTest(
        fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(InstallAgent.CURSOR)),
      ),
    )

    assertEquals(InstallApplyStatus.SUCCESS, repeat.status)
    assertEquals(
      entries.map { entry -> entry.installedPath to entry.cacheTargetPath }.toSet(),
      NativeAgentLinkInventory.read(fixture.home, listOf(currentRoot), fixture.repoRoot)
        .filter { entry -> entry.provider == "cursor" }
        .map { entry -> entry.installedPath to entry.cacheTargetPath }
        .toSet(),
    )
    assertEquals("user cursor file\n", Files.readString(userFile))
  }

  @Test
  fun `cursor apply reconciles a stale managed link from an obsolete generation`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".cursor"))
    val agentDir = fixture.home.resolve(".cursor/agents")
    Files.createDirectories(agentDir)
    val logicalName = "bill-code-review-worker"
    val obsoleteRoot = fixture.home.resolve(
      ".skill-bill/installed-skills/native-agents-old-checkout-0123456789abcdef",
    )
    val obsoleteTarget = NativeAgentProvider.Cursor.cacheArtifactPath(obsoleteRoot, logicalName)
    Files.createDirectories(obsoleteTarget.parent)
    Files.writeString(obsoleteTarget, "---\nname: $logicalName\n---\n")
    val installed = agentDir.resolve(NativeAgentProvider.Cursor.fileName(logicalName))
    createSymlinkOrSkip(installed, obsoleteTarget)

    val result = applyInstallForTest(
      planInstallForTest(
        fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(InstallAgent.CURSOR)),
      ),
    )

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val currentRoot = currentNativeAgentApplyCacheRoot(
      fixture.home,
      fixture.repoRoot.resolve("platform-packs"),
      fixture.repoRoot.resolve("skills"),
    )
    assertEquals(
      NativeAgentProvider.Cursor.cacheArtifactPath(currentRoot, logicalName),
      readSymlinkTarget(installed),
    )
  }

  @Test
  fun `cursor preflight fails with the repair command when a managed link is deleted`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".cursor"))
    val plan = planInstallForTest(
      fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(InstallAgent.CURSOR)),
    )
    assertEquals(InstallApplyStatus.SUCCESS, applyInstallForTest(plan).status)
    val installed = fixture.home.resolve(".cursor/agents")
      .resolve(NativeAgentProvider.Cursor.fileName("bill-code-review-worker"))
    Files.delete(installed)

    val failure = assertFailsWith<MissingInstalledNativeAgentError> {
      FileSystemReviewNativeAgentPreflight(preflightContext(fixture.home)).verify(
        ReviewNativeAgentPreflightRequest(
          repoRoot = fixture.repoRoot,
          agentIds = listOf("cursor"),
          logicalNames = listOf("bill-code-review-worker"),
        ),
      )
    }

    assertContains(failure.message.orEmpty(), "skill-bill install apply")
  }

  @Test
  fun `preflight accepts installed native agents after source checkout is removed`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan = planInstallForTest(
      fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(InstallAgent.CODEX)),
    )
    val result = applyInstallForTest(plan)
    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    Files.walk(fixture.repoRoot).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
    val reviewedRepo = Files.createTempDirectory("skillbill-reviewed-repo").also(tempDirs::add)

    FileSystemReviewNativeAgentPreflight(preflightContext(fixture.home)).verify(
      ReviewNativeAgentPreflightRequest(
        repoRoot = reviewedRepo,
        agentIds = listOf("codex"),
        logicalNames = listOf("bill-code-review-worker"),
      ),
    )
  }
}

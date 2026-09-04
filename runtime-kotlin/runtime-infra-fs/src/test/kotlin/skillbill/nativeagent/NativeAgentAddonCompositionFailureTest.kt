package skillbill.nativeagent

import skillbill.error.ComposedNativeAgentBudgetExceededError
import skillbill.error.InvalidManifestSchemaError
import skillbill.error.MissingContentFileError
import skillbill.install.nativeagent.toNativeAgentPlatformPack
import skillbill.nativeagent.composition.NativeAgentCompositionTarget
import skillbill.nativeagent.composition.NativeAgentCompositionTargetSource
import skillbill.nativeagent.discovery.discoverNativeAgentSourceEntries
import skillbill.nativeagent.rendering.NativeAgentInstallRenderRequest
import skillbill.nativeagent.rendering.NativeAgentOperations
import skillbill.nativeagent.rendering.NativeAgentProvider
import skillbill.nativeagent.rendering.composeGovernedAgentBody
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.testing.HARBOR_ADDON_SLUG
import skillbill.testing.HARBOR_ARCHITECTURE_DIR
import skillbill.testing.HARBOR_ARCHITECTURE_WORKER
import skillbill.testing.HARBOR_AREA_MARKER
import skillbill.testing.HARBOR_COMPANION_NAME
import skillbill.testing.HARBOR_PACK_SLUG
import skillbill.testing.HarborAddonPack
import skillbill.testing.seedHarborAddonPack
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeAgentAddonCompositionFailureTest {
  @Test
  fun `missing entrypoint target names slug slot and absolute path and writes no agent`() {
    val pack = seedHarborAddonPack()
    val missingPath = pack.entrypointPath.toAbsolutePath().normalize()
    Files.delete(pack.entrypointPath)
    val error = assertFailsWith<MissingContentFileError> {
      testComposeNativeAgentSource(pack.repoRoot, architectureSource(pack.repoRoot))
    }
    assertContains(error.message.orEmpty(), "add-on '$HARBOR_ADDON_SLUG'")
    assertContains(error.message.orEmpty(), "slot 'entrypoint'")
    assertContains(error.message.orEmpty(), missingPath.toString())
    assertNoRenderedAgent(pack.repoRoot)
  }

  @Test
  fun `undeclared companion pointer names slug slot and writes no agent`() {
    val pack = seedHarborAddonPack()
    val mutated = withGhostCompanion(loadPlatformPack(pack.packRoot))
    val manifestPath = pack.packRoot.resolve("platform.yaml").toAbsolutePath().normalize()
    val error = assertFailsWith<MissingContentFileError> {
      composeGovernedAgentBody(
        pack.repoRoot,
        NativeAgentCompositionTarget(
          contentPath = pack.architectureContent,
          source = NativeAgentCompositionTargetSource.PlatformManifest,
          manifest = mutated.toNativeAgentPlatformPack(),
        ),
        HARBOR_AREA_MARKER,
      )
    }
    assertContains(error.message.orEmpty(), "add-on '$HARBOR_ADDON_SLUG'")
    assertContains(error.message.orEmpty(), "slot 'ghost-companion.md'")
    assertContains(error.message.orEmpty(), manifestPath.toString())
    assertGhostCompanionRejectedAtLoadAndRender(pack)
  }

  @Test
  fun `unreadable entrypoint target names slug slot and absolute path and writes no agent`() {
    val pack = seedHarborAddonPack()
    val unreadable = pack.entrypointPath.toAbsolutePath().normalize()
    Files.setPosixFilePermissions(pack.entrypointPath, emptySet())
    try {
      val error = assertFailsWith<MissingContentFileError> {
        testComposeNativeAgentSource(pack.repoRoot, architectureSource(pack.repoRoot))
      }
      assertContains(error.message.orEmpty(), "add-on '$HARBOR_ADDON_SLUG'")
      assertContains(error.message.orEmpty(), "slot 'entrypoint'")
      assertContains(error.message.orEmpty(), unreadable.toString())
      assertNoRenderedAgent(pack.repoRoot)
    } finally {
      Files.setPosixFilePermissions(pack.entrypointPath, setOf(PosixFilePermission.OWNER_READ))
    }
  }

  @Test
  fun `over-budget composition names pack skill directory and byte total and writes nothing`() {
    val pack = seedHarborAddonPack()
    Files.writeString(pack.entrypointPath, "x".repeat(8_000))
    Files.createDirectories(pack.repoRoot.resolve(".skill-bill"))
    Files.writeString(
      pack.repoRoot.resolve(".skill-bill/config.yaml"),
      """
      review_context_budget:
        max_parent_packet_bytes: 2000
        max_lane_launch_bytes: 2000
      """.trimIndent() + "\n",
    )
    val error = assertFailsWith<ComposedNativeAgentBudgetExceededError> {
      testComposeNativeAgentSource(pack.repoRoot, architectureSource(pack.repoRoot))
    }
    val message = error.message.orEmpty()
    assertContains(message, "pack '$HARBOR_PACK_SLUG'")
    assertContains(message, "skill directory '$HARBOR_ARCHITECTURE_DIR'")
    assertContains(message, "bytes")
    val total = Regex("""is (\d+) bytes""").find(message)?.groupValues?.get(1)?.toInt()
    assertNotNull(total)
    assertTrue(total > 2000)
    assertNoRenderedAgent(pack.repoRoot)
  }

  private fun architectureSource(repoRoot: Path) = discoverNativeAgentSourceEntries(
    repoRoot.resolve("platform-packs"),
    null,
    listOf(HARBOR_PACK_SLUG),
  ).single { source -> source.name == HARBOR_ARCHITECTURE_WORKER }

  private fun assertNoRenderedAgent(repoRoot: Path) {
    val home = Files.createTempDirectory("skillbill-harbor-fail-home")
    assertTrue(
      runCatching {
        NativeAgentOperations.renderInstallArtifacts(
          NativeAgentInstallRenderRequest(
            platformPacksRoot = repoRoot.resolve("platform-packs"),
            skillsRoot = null,
            selectedPlatforms = listOf(HARBOR_PACK_SLUG),
            provider = NativeAgentProvider.Claude,
            home = home,
            compositionContext = testNativeAgentCompositionContext(repoRoot),
          ),
        )
      }.isFailure,
    )
    NativeAgentProvider.entries.forEach { provider ->
      val cacheRoot = NativeAgentOperations.installCacheRoot(
        home,
        repoRoot.resolve("platform-packs"),
        null,
      )
      assertFalse(
        Files.exists(provider.cacheArtifactPath(cacheRoot, HARBOR_ARCHITECTURE_WORKER)),
        "${provider.directoryName} wrote ${HARBOR_ARCHITECTURE_WORKER} after a composition failure",
      )
    }
  }

  private fun withGhostCompanion(loaded: PlatformManifest): PlatformManifest = loaded.copy(
    addonUsage = loaded.addonUsage.map { usage ->
      usage.copy(
        addons = usage.addons.map { addon ->
          addon.copy(companionPointers = addon.companionPointers + "ghost-companion.md")
        },
      )
    },
  )

  private fun assertGhostCompanionRejectedAtLoadAndRender(pack: HarborAddonPack) {
    val manifest = pack.packRoot.resolve("platform.yaml")
    Files.writeString(
      manifest,
      Files.readString(manifest).replace(
        """
        |      companion_pointers:
        |        - $HARBOR_COMPANION_NAME
        """.trimMargin(),
        """
        |      companion_pointers:
        |        - $HARBOR_COMPANION_NAME
        |        - ghost-companion.md
        """.trimMargin(),
      ),
    )
    val schemaError = assertFailsWith<InvalidManifestSchemaError> { loadPlatformPack(pack.packRoot) }
    assertContains(schemaError.message.orEmpty(), "ghost-companion.md")
    NativeAgentProvider.entries.forEach { provider ->
      assertProviderRejectsGhostCompanion(pack, provider)
    }
  }

  private fun assertProviderRejectsGhostCompanion(pack: HarborAddonPack, provider: NativeAgentProvider) {
    val home = Files.createTempDirectory("skillbill-harbor-undeclared-home")
    val thrown = runCatching {
      NativeAgentOperations.renderInstallArtifacts(
        NativeAgentInstallRenderRequest(
          platformPacksRoot = pack.repoRoot.resolve("platform-packs"),
          skillsRoot = null,
          selectedPlatforms = listOf(HARBOR_PACK_SLUG),
          provider = provider,
          home = home,
          compositionContext = testNativeAgentCompositionContext(pack.repoRoot),
        ),
      )
    }.exceptionOrNull()
    assertTrue(
      thrown is InvalidManifestSchemaError ||
        thrown is MissingContentFileError ||
        (thrown is IllegalArgumentException && thrown.message.orEmpty().contains("ghost-companion.md")),
      "${provider.directoryName} failed with ${thrown?.javaClass?.name}: ${thrown?.message}",
    )
    val cacheRoot = NativeAgentOperations.installCacheRoot(
      home,
      pack.repoRoot.resolve("platform-packs"),
      null,
    )
    assertFalse(
      Files.exists(provider.cacheArtifactPath(cacheRoot, HARBOR_ARCHITECTURE_WORKER)),
      "${provider.directoryName} wrote ${HARBOR_ARCHITECTURE_WORKER} after an undeclared companion",
    )
  }
}

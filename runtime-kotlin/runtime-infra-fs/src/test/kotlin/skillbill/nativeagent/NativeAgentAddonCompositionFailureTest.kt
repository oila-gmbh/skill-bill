package skillbill.nativeagent

import skillbill.error.ComposedNativeAgentBudgetExceededError
import skillbill.error.MissingContentFileError
import skillbill.nativeagent.composition.NativeAgentCompositionTarget
import skillbill.nativeagent.composition.NativeAgentCompositionTargetSource
import skillbill.nativeagent.composition.composeNativeAgentSource
import skillbill.nativeagent.discovery.discoverNativeAgentSourceEntries
import skillbill.nativeagent.rendering.NativeAgentInstallRenderRequest
import skillbill.nativeagent.rendering.NativeAgentOperations
import skillbill.nativeagent.rendering.NativeAgentProvider
import skillbill.nativeagent.rendering.composeGovernedAgentBody
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.testing.HARBOR_ADDON_SLUG
import skillbill.testing.HARBOR_ARCHITECTURE_DIR
import skillbill.testing.HARBOR_ARCHITECTURE_WORKER
import skillbill.testing.HARBOR_AREA_MARKER
import skillbill.testing.HARBOR_COMPANION_NAME
import skillbill.testing.HARBOR_ENTRYPOINT_NAME
import skillbill.testing.HARBOR_PACK_SLUG
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
      composeNativeAgentSource(pack.repoRoot, architectureSource(pack.repoRoot))
    }
    assertContains(error.message.orEmpty(), "add-on '$HARBOR_ADDON_SLUG'")
    assertContains(error.message.orEmpty(), "slot 'entrypoint'")
    assertContains(error.message.orEmpty(), missingPath.toString())
    assertNoRenderedAgent(pack.repoRoot)
  }

  @Test
  fun `undeclared companion pointer names slug slot and writes no agent`() {
    val pack = seedHarborAddonPack()
    val loaded = loadPlatformPack(pack.packRoot)
    val mutated = loaded.copy(
      addonUsage = loaded.addonUsage.map { usage ->
        usage.copy(
          addons = usage.addons.map { addon ->
            addon.copy(companionPointers = addon.companionPointers + "ghost-companion.md")
          },
        )
      },
    )
    val error = assertFailsWith<MissingContentFileError> {
      composeGovernedAgentBody(
        pack.repoRoot,
        NativeAgentCompositionTarget(
          contentPath = pack.architectureContent,
          source = NativeAgentCompositionTargetSource.PlatformManifest,
          manifest = mutated,
        ),
        HARBOR_AREA_MARKER,
      )
    }
    assertContains(error.message.orEmpty(), "add-on '$HARBOR_ADDON_SLUG'")
    assertContains(error.message.orEmpty(), "slot 'ghost-companion.md'")
    val manifest = pack.packRoot.resolve("platform.yaml")
    Files.writeString(
      manifest,
      Files.readString(manifest).replace(
        """
        |    - name: $HARBOR_COMPANION_NAME
        |      target: platform-packs/$HARBOR_PACK_SLUG/addons/$HARBOR_COMPANION_NAME
        """.trimMargin(),
        "",
      ),
    )
    assertNoRenderedAgent(pack.repoRoot)
  }

  @Test
  fun `unreadable entrypoint target names slug slot and absolute path and writes no agent`() {
    val pack = seedHarborAddonPack()
    val unreadable = pack.entrypointPath.toAbsolutePath().normalize()
    Files.setPosixFilePermissions(pack.entrypointPath, emptySet())
    try {
      val error = assertFailsWith<MissingContentFileError> {
        composeNativeAgentSource(pack.repoRoot, architectureSource(pack.repoRoot))
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
      composeNativeAgentSource(pack.repoRoot, architectureSource(pack.repoRoot))
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
}

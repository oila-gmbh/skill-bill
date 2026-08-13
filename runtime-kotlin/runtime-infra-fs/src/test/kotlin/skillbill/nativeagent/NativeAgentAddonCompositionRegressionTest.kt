package skillbill.nativeagent

import skillbill.nativeagent.composition.composeNativeAgentSource
import skillbill.nativeagent.discovery.discoverNativeAgentSourceEntries
import skillbill.nativeagent.rendering.NativeAgentInstallRenderOverrides
import skillbill.nativeagent.rendering.NativeAgentInstallRenderRequest
import skillbill.nativeagent.rendering.NativeAgentOperations
import skillbill.nativeagent.rendering.NativeAgentProvider
import skillbill.testing.HARBOR_ADDON_SLUG
import skillbill.testing.HARBOR_ARCHITECTURE_WORKER
import skillbill.testing.HARBOR_AREA_MARKER
import skillbill.testing.HARBOR_BASELINE_MARKER
import skillbill.testing.HARBOR_COMPANION_MARKER
import skillbill.testing.HARBOR_COMPANION_NAME
import skillbill.testing.HARBOR_ENTRYPOINT_MARKER
import skillbill.testing.HARBOR_ENTRYPOINT_NAME
import skillbill.testing.HARBOR_PACK_SLUG
import skillbill.testing.seedHarborAddonPack
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeAgentAddonCompositionRegressionTest {
  @Test
  fun `addon_usage without a content md link reaches the rendered agent body`() {
    val pack = seedHarborAddonPack()
    assertFalse("]($HARBOR_ENTRYPOINT_NAME)" in Files.readString(pack.architectureContent))
    assertFalse("]($HARBOR_COMPANION_NAME)" in Files.readString(pack.architectureContent))

    val rendered = renderArchitecture(pack.repoRoot, NativeAgentProvider.Claude)

    assertContains(rendered, HARBOR_ENTRYPOINT_MARKER)
    assertContains(rendered, HARBOR_COMPANION_MARKER)
    assertContains(rendered, "### Add-On: $HARBOR_ADDON_SLUG")
  }

  @Test
  fun `two renders from the same manifest and add-on files are byte identical per provider`() {
    val pack = seedHarborAddonPack()
    NativeAgentProvider.entries.forEach { provider ->
      val firstHome = Files.createTempDirectory("skillbill-harbor-idem-a")
      val secondHome = Files.createTempDirectory("skillbill-harbor-idem-b")
      val firstRoot = Files.createTempDirectory("skillbill-harbor-cache-a")
      val secondRoot = Files.createTempDirectory("skillbill-harbor-cache-b")
      val first = renderInstall(pack.repoRoot, provider, firstHome, firstRoot)
      val second = renderInstall(pack.repoRoot, provider, secondHome, secondRoot)
      val firstBytes = Files.readAllBytes(architectureArtifact(first.generatedFiles, provider))
      val secondBytes = Files.readAllBytes(architectureArtifact(second.generatedFiles, provider))
      assertTrue(
        firstBytes.contentEquals(secondBytes),
        "${provider.directoryName} output diverged across repeated renders",
      )
    }
  }

  @Test
  fun `rendered body order is baseline then area then entrypoint before companion`() {
    val pack = seedHarborAddonPack()
    val baseline = composeNativeAgentSource(
      pack.repoRoot,
      harborSources(pack.repoRoot).single { source -> source.name == "bill-harbor-code-review" },
    ).body
    val area = composeNativeAgentSource(pack.repoRoot, architectureSource(pack.repoRoot)).body
    val body = baseline + area
    assertTrue(
      body.indexOf(HARBOR_BASELINE_MARKER) >= 0 &&
        body.indexOf(HARBOR_BASELINE_MARKER) < body.indexOf(HARBOR_AREA_MARKER) &&
        body.indexOf(HARBOR_AREA_MARKER) < body.indexOf(HARBOR_ENTRYPOINT_MARKER) &&
        body.indexOf(HARBOR_ENTRYPOINT_MARKER) < body.indexOf(HARBOR_COMPANION_MARKER),
      "baseline content, then area content, then entrypoint before companion",
    )
  }

  @Test
  fun `a file reachable as both a declared add-on and a linked sidecar appears once`() {
    val pack = seedHarborAddonPack(linkEntrypointFromArea = true)
    val body = composeNativeAgentSource(pack.repoRoot, architectureSource(pack.repoRoot)).body
    assertEquals(1, markerCount(body, HARBOR_ENTRYPOINT_MARKER))
    assertFalse("## Inlined Reference: $HARBOR_ENTRYPOINT_NAME" in body)
  }

  @Test
  fun `composed add-on marker is present in every provider render target`() {
    val pack = seedHarborAddonPack()
    NativeAgentProvider.entries.forEach { provider ->
      val rendered = renderArchitecture(pack.repoRoot, provider)
      assertContains(
        rendered,
        HARBOR_ENTRYPOINT_MARKER,
        "${provider.directoryName} is missing composed add-on content",
      )
    }
  }

  private fun renderArchitecture(repoRoot: Path, provider: NativeAgentProvider): String {
    val result = renderInstall(repoRoot, provider, Files.createTempDirectory("skillbill-harbor-render"))
    return Files.readString(architectureArtifact(result.generatedFiles, provider))
  }

  private fun renderInstall(
    repoRoot: Path,
    provider: NativeAgentProvider,
    home: Path,
    cacheRoot: Path? = null,
  ) = NativeAgentOperations.renderInstallArtifacts(
    NativeAgentInstallRenderRequest(
      platformPacksRoot = repoRoot.resolve("platform-packs"),
      skillsRoot = null,
      selectedPlatforms = listOf(HARBOR_PACK_SLUG),
      provider = provider,
      home = home,
      overrides = NativeAgentInstallRenderOverrides(cacheRoot = cacheRoot),
    ),
  )

  private fun architectureArtifact(generated: List<Path>, provider: NativeAgentProvider): Path =
    generated.single { path -> path.fileName.toString() == provider.fileName(HARBOR_ARCHITECTURE_WORKER) }

  private fun harborSources(repoRoot: Path) = discoverNativeAgentSourceEntries(
    repoRoot.resolve("platform-packs"),
    null,
    listOf(HARBOR_PACK_SLUG),
  )

  private fun architectureSource(repoRoot: Path) =
    harborSources(repoRoot).single { source -> source.name == HARBOR_ARCHITECTURE_WORKER }

  private fun markerCount(body: String, marker: String): Int = body.split(marker).size - 1
}

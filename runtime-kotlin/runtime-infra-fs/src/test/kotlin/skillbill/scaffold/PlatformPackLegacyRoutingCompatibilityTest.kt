package skillbill.scaffold

import skillbill.scaffold.platformpack.loadPlatformManifest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformPackLegacyRoutingCompatibilityTest {
  @Test
  fun `omitted routed lane metadata preserves legacy manifest behavior`() {
    val manifest = """
      platform: scenarioslug
      contract_version: "1.2"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: [architecture]
      declared_files:
        baseline: code-review/bill-scenarioslug-code-review/content.md
        areas:
          architecture: code-review/bill-scenarioslug-code-review-architecture/content.md
      pointers:
        code-review/bill-scenarioslug-code-review:
          - name: legacy-review.md
            target: platform-packs/scenarioslug/addons/legacy-review.md
      addon_usage:
        code-review/bill-scenarioslug-code-review:
          - slug: legacy
            entrypoint: legacy-review.md
    """.trimIndent()
    val root = Files.createTempDirectory("skillbill-legacy-platform-pack-test-")
      .resolve("platform-packs/scenarioslug")
    Files.createDirectories(root)
    Files.writeString(root.resolve("platform.yaml"), manifest)

    val pack = loadPlatformManifest(root)

    assertTrue(pack.laneConditions.isEmpty())
    assertEquals(listOf(".kt"), pack.routingSignals.path)
    assertNull(pack.addonUsage.single().addons.single().activation)
  }
}

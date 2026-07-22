package skillbill.infrastructure.fs

import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.GovernedAddonActivation
import skillbill.scaffold.model.GovernedAddonUsage
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileSystemReviewRubricResolverTest {
  @Test
  fun `manifest baseline is the authoritative rubric source`() {
    val root = Files.createTempDirectory("review-rubric")
    val baseline = root.resolve("code-review/content.md")
    Files.createDirectories(baseline.parent)
    Files.writeString(baseline, "governed kotlin review rubric")

    val resolved = FileSystemReviewRubricResolver().resolve(manifest(root, baseline))

    assertEquals("bill-kotlin-code-review", resolved.rubricId)
    assertEquals("governed kotlin review rubric", resolved.body)
  }

  @Test
  fun `routed rubric keeps every declared specialist separate`() {
    val root = Files.createTempDirectory("review-rubric")
    val baseline = root.resolve("code-review/content.md")
    val security = root.resolve("code-review/security/content.md")
    Files.createDirectories(security.parent)
    Files.writeString(baseline, "baseline rubric")
    Files.writeString(security, "security specialist rubric")

    val resolved = FileSystemReviewRubricResolver().resolve(
      manifest(root, baseline, mapOf("security" to security)),
    )

    assertEquals("baseline rubric", resolved.body)
    assertEquals(1, resolved.specialists.size)
    assertEquals("security", resolved.specialists.single().area)
    assertEquals("bill-kotlin-code-review-security", resolved.specialists.single().rubricId)
    assertEquals("security specialist rubric", resolved.specialists.single().body)
  }

  @Test
  fun `manifest baseline cannot escape its pack`() {
    val root = Files.createTempDirectory("review-rubric")
    val outside = Files.createTempFile("outside-rubric", ".md")

    assertFailsWith<IllegalArgumentException> {
      FileSystemReviewRubricResolver().resolve(manifest(root, outside))
    }
  }

  @Test
  fun `selected add-on guidance is composed into one specialist rubric`() {
    val root = Files.createTempDirectory("review-rubric-addon")
    val baseline = root.resolve("code-review/content.md")
    val ui = root.resolve("code-review/ui/content.md")
    val addon = root.resolve("addons/android-compose-review.md")
    Files.createDirectories(ui.parent)
    Files.createDirectories(addon.parent)
    Files.writeString(baseline, "baseline rubric")
    Files.writeString(ui, "ui specialist rubric")
    Files.writeString(addon, "## Activation signals\n\n- `@Composable` functions and `LaunchedEffect`")
    val base = manifest(root, baseline, mapOf("ui" to ui))
    val configured = base.copy(
      addonUsage = listOf(
        GovernedAddonUsage(
          "code-review/bill-kotlin-code-review-ui",
          listOf(
            GovernedAddonSelection(
              "android-compose",
              "android-compose-review.md",
              activation = GovernedAddonActivation(anyContent = listOf("@composable")),
            ),
          ),
        ),
      ),
    )

    val resolved = FileSystemReviewRubricResolver().resolve(
      configured,
      "+ @Composable fun Screen() = Unit",
      "bill-kotlin-code-review-ui",
    )

    assertEquals(listOf("android-compose"), resolved.selectedAddOns)
    assertEquals(true, resolved.body.contains("Selected governed add-on guidance"))
    assertEquals(true, resolved.body.contains("@Composable"))
  }

  @Test
  fun `commonMain only scope excludes Android add-ons`() {
    val root = Files.createTempDirectory("review-rubric-common")
    val baseline = root.resolve("code-review/content.md")
    val ui = root.resolve("code-review/ui/content.md")
    val addon = root.resolve("addons/android-compose-review.md")
    Files.createDirectories(ui.parent)
    Files.createDirectories(addon.parent)
    Files.writeString(baseline, "baseline rubric")
    Files.writeString(ui, "ui specialist rubric")
    Files.writeString(addon, "## Activation signals\n\n- `@Composable` functions")
    val configured = manifest(root, baseline, mapOf("ui" to ui)).copy(
      addonUsage = listOf(
        GovernedAddonUsage(
          "code-review/bill-kotlin-code-review-ui",
          listOf(
            GovernedAddonSelection(
              "android-compose",
              "android-compose-review.md",
              activation = GovernedAddonActivation(
                anyContent = listOf("@composable"),
                excludePath = listOf("/commonMain/"),
              ),
            ),
          ),
        ),
      ),
    )

    val resolved = FileSystemReviewRubricResolver().resolve(
      configured,
      "+++ b/src/commonMain/kotlin/Screen.kt\n+ @Composable fun Screen() = Unit",
      "bill-kotlin-code-review-ui",
    )

    assertEquals(emptyList(), resolved.selectedAddOns)
    assertEquals("ui specialist rubric", resolved.body)
  }

  @Test
  fun `baseline R8 add-on reaches the direct platform correctness specialist`() {
    val root = Files.createTempDirectory("review-rubric-r8")
    val baseline = root.resolve("code-review/content.md")
    val correctness = root.resolve("code-review/platform-correctness/content.md")
    val addon = root.resolve("addons/android-r8-review.md")
    Files.createDirectories(correctness.parent)
    Files.createDirectories(addon.parent)
    Files.writeString(baseline, "baseline rubric")
    Files.writeString(correctness, "correctness specialist rubric")
    Files.writeString(addon, "R8 bounded guidance")
    val configured = manifest(root, baseline, mapOf("platform-correctness" to correctness)).copy(
      addonUsage = listOf(
        GovernedAddonUsage(
          "code-review/bill-kotlin-code-review",
          listOf(
            GovernedAddonSelection(
              "android-r8",
              "android-r8-review.md",
              activation = GovernedAddonActivation(anyPath = listOf("proguard-rules.pro")),
              specialistAreas = listOf("platform-correctness"),
            ),
          ),
        ),
      ),
    )

    val resolved = FileSystemReviewRubricResolver().resolve(
      configured,
      "+++ b/android/proguard-rules.pro\n+ -keep class example.Model",
      "bill-kotlin-code-review-platform-correctness",
    )

    assertEquals(listOf("android-r8"), resolved.selectedAddOns)
    assertEquals(true, resolved.body.contains("R8 bounded guidance"))
  }

  @Test
  fun `conjunctive activation rejects local store without sync`() {
    val root = Files.createTempDirectory("review-rubric-offline")
    val baseline = root.resolve("code-review/content.md")
    val persistence = root.resolve("code-review/persistence/content.md")
    val addon = root.resolve("addons/offline-first-review.md")
    Files.createDirectories(persistence.parent)
    Files.createDirectories(addon.parent)
    Files.writeString(baseline, "baseline rubric")
    Files.writeString(persistence, "persistence specialist rubric")
    Files.writeString(addon, "offline bounded guidance")
    val configured = manifest(root, baseline, mapOf("persistence" to persistence)).copy(
      addonUsage = listOf(
        GovernedAddonUsage(
          "code-review/bill-kotlin-code-review-persistence",
          listOf(
            GovernedAddonSelection(
              "offline-first",
              "offline-first-review.md",
              activation = GovernedAddonActivation(anyOfAllContent = listOf(listOf("sqlite", "sync"))),
            ),
          ),
        ),
      ),
    )
    val resolver = FileSystemReviewRubricResolver()

    assertEquals(
      emptyList(),
      resolver.resolve(configured, "+ SQLite migration", "bill-kotlin-code-review-persistence").selectedAddOns,
    )
    assertEquals(
      listOf("offline-first"),
      resolver.resolve(configured, "+ SQLite sync queue", "bill-kotlin-code-review-persistence").selectedAddOns,
    )
  }

  private fun manifest(
    root: java.nio.file.Path,
    baseline: java.nio.file.Path,
    areas: Map<String, java.nio.file.Path> = emptyMap(),
  ) = PlatformManifest(
    slug = "kotlin",
    packRoot = root,
    contractVersion = "1.2",
    routingSignals = RoutingSignals(listOf(".kt"), emptyList()),
    declaredCodeReviewAreas = areas.keys.toList(),
    declaredFiles = DeclaredFiles(baseline, areas),
    areaMetadata = emptyMap(),
  )
}

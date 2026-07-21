package skillbill.infrastructure.fs

import skillbill.scaffold.model.DeclaredFiles
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
  fun `manifest baseline cannot escape its pack`() {
    val root = Files.createTempDirectory("review-rubric")
    val outside = Files.createTempFile("outside-rubric", ".md")

    assertFailsWith<IllegalArgumentException> {
      FileSystemReviewRubricResolver().resolve(manifest(root, outside))
    }
  }

  private fun manifest(root: java.nio.file.Path, baseline: java.nio.file.Path) = PlatformManifest(
    slug = "kotlin",
    packRoot = root,
    contractVersion = "1.2",
    routingSignals = RoutingSignals(listOf(".kt"), emptyList()),
    declaredCodeReviewAreas = emptyList(),
    declaredFiles = DeclaredFiles(baseline, emptyMap()),
    areaMetadata = emptyMap(),
  )
}

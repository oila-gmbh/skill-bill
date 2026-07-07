package skillbill.infrastructure.fs

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FileSystemReviewAttributionTest {
  @Test
  fun `platform review attribution mappings are derived from discovered manifests`() {
    val repoRoot = Files.createTempDirectory("skillbill-review-attribution")
    val packRoot = repoRoot.resolve("platform-packs/ruby")
    val baseline = packRoot.resolve("code-review/bill-ruby-code-review/content.md")
    val security = packRoot.resolve("code-review/bill-ruby-code-review-security/content.md")
    Files.createDirectories(baseline.parent)
    Files.createDirectories(security.parent)
    Files.writeString(packRoot.resolve("platform.yaml"), platformManifest())
    Files.writeString(baseline, content("bill-ruby-code-review"))
    Files.writeString(security, content("bill-ruby-code-review-security"))

    val mappings = platformReviewAttributionMappings(repoRoot.resolve("platform-packs"))

    assertEquals("ruby", mappings["bill-ruby-code-review"])
    assertEquals("ruby", mappings["bill-ruby-code-review-security"])
    assertEquals(setOf("bill-ruby-code-review", "bill-ruby-code-review-security"), mappings.keys)
  }

  private fun platformManifest(): String = """
    |platform: ruby
    |contract_version: "1.2"
    |display_name: Ruby
    |routing_signals:
    |  strong:
    |    - Gemfile
    |  tie_breakers: []
    |declared_code_review_areas:
    |  - security
    |declared_files:
    |  baseline: code-review/bill-ruby-code-review/content.md
    |  areas:
    |    security: code-review/bill-ruby-code-review-security/content.md
    |area_metadata:
    |  security:
    |    focus: Ruby security review.
    |
  """.trimMargin()

  private fun content(name: String): String = """
    |---
    |name: $name
    |description: Review Ruby code.
    |---
    |
    |Review Ruby changes.
    |
  """.trimMargin()
}

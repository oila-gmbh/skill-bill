package skillbill.workflow

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class FeatureSpecSkillWiringContractTest {
  @Test
  fun `bill feature spec content defines governed intake and modes`() {
    val content = Files.readString(repoRootFromTest().resolve("skills/bill-feature-spec/content.md"))

    assertContains(content, "name: bill-feature-spec")
    assertContains(content, "If the issue key is missing, stop and ask for it.")
    assertContains(content, "single_spec")
    assertContains(content, "decomposed")
    assertContains(content, "Do not fork logic between `bill-feature-spec`, `bill-feature-implement`, and `bill-goal`.")
  }

  @Test
  fun `bill feature implement content routes decomposition through shared preparation path`() {
    val content = Files.readString(repoRootFromTest().resolve("skills/bill-feature-implement/content.md"))

    assertContains(content, "## Shared Feature-Spec Preparation Path")
    assertContains(content, "invoke the shared feature-spec preparation path")
  }

  @Test
  fun `bill goal content reuses shared preparation and keeps goal runner consumer only`() {
    val content = Files.readString(repoRootFromTest().resolve("skills/bill-goal/content.md"))
    val featureSpecContent = Files.readString(repoRootFromTest().resolve("skills/bill-feature-spec/content.md"))

    assertContains(content, "invoke `bill-feature-spec` in this session")
    assertContains(content, "`skill-bill goal <issue_key>` remains consumer-only")
    assertContains(featureSpecContent, "`skill-bill goal <issue_key>` is consumer-only")
    assertContains(content, "Ask one confirmation question")
    assertEquals(1, countOccurrences(content, "Ask one confirmation question"))
  }
}

private fun countOccurrences(haystack: String, needle: String): Int =
  Regex(Regex.escape(needle)).findAll(haystack).count()

private fun repoRootFromTest(): Path {
  var current = Path.of("").toAbsolutePath().normalize()
  while (current.parent != null) {
    val hasSettings = Files.isRegularFile(current.resolve("runtime-kotlin/settings.gradle.kts"))
    val hasContracts = Files.isDirectory(current.resolve("orchestration/contracts"))
    if (hasSettings && hasContracts) {
      return current
    }
    current = current.parent
  }
  error("Could not locate skill-bill repo root from ${Path.of("").toAbsolutePath().normalize()}")
}

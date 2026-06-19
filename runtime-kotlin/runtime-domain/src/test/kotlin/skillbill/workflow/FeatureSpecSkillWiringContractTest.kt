package skillbill.workflow

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class FeatureSpecSkillWiringContractTest {
  @Test
  fun `bill feature content routes through spec preparation before execution`() {
    val content = Files.readString(repoRootFromTest().resolve("skills/bill-feature/content.md"))

    assertContains(content, "name: bill-feature")
    assertContains(content, "Always invoke `bill-feature-spec` first")
    assertContains(content, "Treat its selected mode as authoritative for dispatch")
    assertContains(content, "For `single_spec` output")
    assertContains(content, "Run `bill-feature-task` on `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md`")
    assertContains(content, "For `decomposed` output")
    assertContains(content, "Invoke `bill-feature-goal` in the current session")
    assertContains(content, "Do not ask an extra confirmation before invoking `bill-feature-goal`")
  }

  @Test
  fun `bill feature spec content defines governed intake and modes`() {
    val content = Files.readString(repoRootFromTest().resolve("skills/bill-feature-spec/content.md"))

    assertContains(content, "name: bill-feature-spec")
    assertContains(content, "If the issue key is missing, stop and ask for it.")
    assertContains(content, "single_spec")
    assertContains(content, "decomposed")
    assertContains(
      content,
      "Do not fork logic between `bill-feature-spec`, `bill-feature-task`, and `bill-feature-goal`.",
    )
  }

  @Test
  fun `bill feature task prose content routes decomposition through shared preparation path`() {
    val content = Files.readString(repoRootFromTest().resolve("skills/bill-feature-task-prose/content.md"))

    assertContains(content, "## Shared Feature-Spec Preparation Path")
    assertContains(content, "invoke the shared feature-spec preparation path")
  }

  @Test
  fun `bill feature goal content reuses shared preparation and keeps goal runner consumer only`() {
    val content = Files.readString(repoRootFromTest().resolve("skills/bill-feature-goal/content.md"))
    val featureSpecContent = Files.readString(repoRootFromTest().resolve("skills/bill-feature-spec/content.md"))

    assertContains(content, "invoke `bill-feature-spec` in this session")
    assertContains(content, "`bill-feature-goal` is the trigger surface for decomposed-goal orchestration")
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

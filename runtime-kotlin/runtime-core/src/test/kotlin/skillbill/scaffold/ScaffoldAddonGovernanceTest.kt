package skillbill.scaffold

import skillbill.error.InvalidScaffoldPayloadError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ScaffoldAddonGovernanceTest {
  @Test
  fun `add-on scaffold rejects explicit consumer dirs that are not declared skills`() = withIsolatedUserHome {
    val repo = seedRepo()
    Files.createDirectories(repo.resolve("platform-packs/kotlin/code-review/not-a-declared-skill"))

    val error = assertFailsWith<InvalidScaffoldPayloadError> {
      scaffold(
        payload(
          repo,
          "add-on",
          "platform" to "kotlin",
          "name" to "orphan-helper",
          "consumer_skill_dirs" to listOf("code-review/not-a-declared-skill"),
        ),
      )
    }

    assertContains(error.message.orEmpty(), "not declared as a skill")
    assertFalse(Files.exists(repo.resolve("platform-packs/kotlin/addons/orphan-helper.md")))
  }

  @Test
  fun `add-on manifest registration preserves hyphenated custom top-level fields`() {
    val manifest = """
      platform: kotlin
      contract_version: "1.1"
      routing_signals:
        strong:
          - ".kt"
      declared_code_review_areas: []
      declared_files:
        baseline: code-review/bill-kotlin-code-review/content.md
        areas: {}
      pointers:
        code-review/bill-kotlin-code-review:
          - name: shell-ceremony.md
            target: orchestration/shell-content-contract/shell-ceremony.md
      fork-specific-field:
        owner: local
    """.trimIndent() + "\n"

    val updated = renderGovernedAddonManifestRegistration(
      text = manifest,
      platform = "kotlin",
      skillRelativeDirs = listOf("code-review/bill-kotlin-code-review"),
      addonSlug = "review-helper",
    )

    assertContains(
      updated,
      "    - name: \"review-helper.md\"\n      target: \"platform-packs/kotlin/addons/review-helper.md\"",
    )
    assertContains(updated, "\nfork-specific-field:\n  owner: local\n")
    assertFalse("fork-specific-field:\n    - name: \"review-helper.md\"" in updated)
  }
}

private fun payload(repo: Path, kind: String, vararg pairs: Pair<String, Any?>): Map<String, Any?> =
  mapOf("scaffold_payload_version" to "1.0", "kind" to kind, "repo_root" to repo.toString()) + pairs

private fun seedRepo(): Path {
  val repo = Files.createTempDirectory("skillbill-scaffold-addon-repo")
  val packRoot = repo.resolve("platform-packs/kotlin")
  val baseline = packRoot.resolve("code-review/bill-kotlin-code-review")
  Files.createDirectories(baseline)
  val context = TemplateContext("bill-kotlin-code-review", "code-review", "kotlin", "", "Kotlin")
  Files.writeString(
    packRoot.resolve("platform.yaml"),
    renderPlatformPackManifest(
      platform = "kotlin",
      displayName = "Kotlin",
      strongSignals = listOf(".kt"),
      baselineContentPath = "code-review/bill-kotlin-code-review/content.md",
    ),
  )
  Files.writeString(
    baseline.resolve("content.md"),
    renderContentBody(context, inferSkillDescription(context)),
  )
  return repo
}

private fun withIsolatedUserHome(block: () -> Unit) {
  val previousHome = System.getProperty("user.home")
  val tempHome = Files.createTempDirectory("skillbill-scaffold-addon-home").toString()
  try {
    System.setProperty("user.home", tempHome)
    block()
  } finally {
    System.setProperty("user.home", previousHome)
  }
}

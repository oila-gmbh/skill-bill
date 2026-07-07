package skillbill.scaffold

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.scaffold.manifest.renderGovernedAddonManifestRegistration
import skillbill.scaffold.policy.renderPlatformPackManifest
import skillbill.scaffold.rendering.inferSkillDescription
import skillbill.scaffold.rendering.renderContentBody
import skillbill.scaffold.runtime.TemplateContext
import skillbill.scaffold.runtime.scaffold
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ScaffoldAddonGovernanceTest {
  @Test
  fun `add-on scaffold rejects explicit consumer dirs that are not declared skills`() = withIsolatedUserHome {
    val repo = seedRepo()
    Files.createDirectories(repo.resolve("platform-packs/kotlin/code-review/not-a-declared-skill"))
    val manifestPath = repo.resolve("platform-packs/kotlin/platform.yaml")
    val beforeManifest = Files.readString(manifestPath)
    val beforeTree = snapshotRepoTree(repo)

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
    assertEquals(beforeManifest, Files.readString(manifestPath))
    assertEquals(beforeTree, snapshotRepoTree(repo))
  }

  @Test
  fun `add-on scaffold rejects unsafe consumer dirs before mutation`() = withIsolatedUserHome {
    val repo = seedRepo()
    val manifestPath = repo.resolve("platform-packs/kotlin/platform.yaml")
    val beforeManifest = Files.readString(manifestPath)
    val beforeTree = snapshotRepoTree(repo)

    val error = assertFailsWith<InvalidScaffoldPayloadError> {
      scaffold(
        payload(
          repo,
          "add-on",
          "platform" to "kotlin",
          "name" to "unsafe-helper",
          "consumer_skill_dirs" to listOf("../code-review/bill-kotlin-code-review"),
        ),
      )
    }

    assertContains(error.message.orEmpty(), "must not contain '..' segments")
    assertFalse(Files.exists(repo.resolve("platform-packs/kotlin/addons/unsafe-helper.md")))
    assertEquals(beforeManifest, Files.readString(manifestPath))
    assertEquals(beforeTree, snapshotRepoTree(repo))
  }

  @Test
  fun `add-on scaffold defaults to single declared non-baseline consumer`() = withIsolatedUserHome {
    val repo = seedQualityCheckOnlyRepo()

    scaffold(
      payload(
        repo,
        "add-on",
        "platform" to "qualityonly",
        "name" to "lint-helper",
      ),
    )
    val manifest = Files.readString(repo.resolve("platform-packs/qualityonly/platform.yaml"))

    assertContains(manifest, "  quality-check/bill-qualityonly-code-check:")
    assertContains(manifest, "    - name: \"lint-helper.md\"")
    assertContains(manifest, "addon_usage:")
    assertContains(manifest, "    - slug: \"lint-helper\"")
    assertContains(manifest, "      entrypoint: \"lint-helper.md\"")
  }

  @Test
  fun `add-on scaffold can write external addon source manifest`() = withIsolatedUserHome {
    val repo = seedRepo()
    val externalDir = Files.createTempDirectory("skillbill-scaffold-external-addon")
    val platformManifestPath = repo.resolve("platform-packs/kotlin/platform.yaml")
    val beforePlatformManifest = Files.readString(platformManifestPath)

    scaffold(
      payload(
        repo,
        "add-on",
        "platform" to "kotlin",
        "name" to "private-review",
        "addon_location_path" to externalDir.toString(),
      ),
    )

    assertEquals(beforePlatformManifest, Files.readString(platformManifestPath))
    assertContains(Files.readString(externalDir.resolve("private-review.md")), "# private-review")
    val externalManifest = Files.readString(externalDir.resolve("addon-manifest.yaml"))
    assertContains(externalManifest, "  code-review/bill-kotlin-code-review:")
    assertContains(externalManifest, "    - name: \"private-review.md\"")
    assertContains(externalManifest, "      target: \"private-review.md\"")
    assertContains(externalManifest, "    - slug: \"private-review\"")
    assertContains(externalManifest, "      entrypoint: \"private-review.md\"")
  }

  @Test
  fun `add-on scaffold rejects omitted consumers when pack has no default before mutation`() = withIsolatedUserHome {
    val repo = seedAddonOnlyRepo()
    val beforeTree = snapshotRepoTree(repo)

    val error = assertFailsWith<InvalidScaffoldPayloadError> {
      scaffold(
        payload(
          repo,
          "add-on",
          "platform" to "addononly",
          "name" to "orphan-helper",
        ),
      )
    }

    assertContains(error.message.orEmpty(), "omitted 'consumer_skill_dirs'")
    assertContains(error.message.orEmpty(), "no unambiguous default consumer")
    assertEquals(beforeTree, snapshotRepoTree(repo))
  }

  @Test
  fun `add-on manifest registration preserves hyphenated custom top-level fields`() {
    val manifest = """
      platform: kotlin
      contract_version: "1.2"
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

private fun seedQualityCheckOnlyRepo(): Path {
  val repo = Files.createTempDirectory("skillbill-scaffold-addon-quality-repo")
  val packRoot = repo.resolve("platform-packs/qualityonly")
  val qualityCheck = packRoot.resolve("quality-check/bill-qualityonly-code-check")
  Files.createDirectories(qualityCheck)
  Files.writeString(
    packRoot.resolve("platform.yaml"),
    """
    platform: qualityonly
    contract_version: "1.2"
    display_name: "Quality Only"

    routing_signals:
      strong:
        - "qualityonly.marker"
      tie_breakers: []

    declared_code_review_areas: []

    declared_quality_check_file: "quality-check/bill-qualityonly-code-check/content.md"
    """.trimIndent() + "\n",
  )
  val context = TemplateContext("bill-qualityonly-code-check", "quality-check", "qualityonly", "", "Quality Only")
  Files.writeString(
    qualityCheck.resolve("content.md"),
    renderContentBody(context, inferSkillDescription(context), internalFor = "bill-code-check"),
  )
  return repo
}

private fun seedAddonOnlyRepo(): Path {
  val repo = Files.createTempDirectory("skillbill-scaffold-addon-only-repo")
  val packRoot = repo.resolve("platform-packs/addononly")
  Files.createDirectories(packRoot)
  Files.writeString(
    packRoot.resolve("platform.yaml"),
    """
    platform: addononly
    contract_version: "1.2"
    display_name: "Add-On Only"

    routing_signals:
      strong:
        - "addononly.marker"
      tie_breakers: []

    declared_code_review_areas: []
    """.trimIndent() + "\n",
  )
  return repo
}

private data class RepoTreeSnapshot(
  val directories: List<String>,
  val files: Map<String, String>,
)

private fun snapshotRepoTree(repo: Path): RepoTreeSnapshot {
  val directories = mutableListOf<String>()
  val files = linkedMapOf<String, String>()
  Files.walk(repo).use { paths ->
    paths
      .filter { path -> path != repo }
      .forEach { path -> snapshotPath(repo, path, directories, files) }
  }
  return RepoTreeSnapshot(
    directories = directories.sorted(),
    files = files.toSortedMap(),
  )
}

private fun snapshotPath(repo: Path, path: Path, directories: MutableList<String>, files: MutableMap<String, String>) {
  val relative = repo.relativize(path).toString().replace('\\', '/')
  if (path.isDirectory()) {
    directories += relative
  } else {
    files[relative] = Files.readString(path)
  }
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

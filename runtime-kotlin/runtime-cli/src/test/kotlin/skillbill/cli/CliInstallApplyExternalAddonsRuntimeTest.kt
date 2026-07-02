package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonSupport
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliInstallApplyExternalAddonsRuntimeTest {
  @Test
  fun `install apply-external-addons is registered and shows help`() {
    val result = CliRuntime.run(listOf("install", "apply-external-addons", "--help"), CliRuntimeContext())

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "external addon overlay")
  }

  @Test
  fun `absent machine-global config is a clean no-op`() {
    val repo = Files.createTempDirectory("ext-addon-apply-no-config")
    Files.createDirectories(repo.resolve("platform-packs"))
    val home = Files.createTempDirectory("ext-addon-apply-home")

    val result = CliRuntime.run(
      listOf("install", "apply-external-addons", "--repo-root", repo.toString()),
      CliRuntimeContext(
        userHome = home,
        environment = mapOf(CONFIG_ENVIRONMENT_KEY to home.resolve("config.json").toString()),
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "no external addon sources")
  }

  @Test
  fun `config referencing a non-installed platform is skipped with a warning`() {
    val repo = Files.createTempDirectory("ext-addon-apply-skip")
    Files.createDirectories(repo.resolve("platform-packs"))
    val home = Files.createTempDirectory("ext-addon-apply-skip-home")
    val sourceDir = Files.createDirectories(home.resolve("private/android"))
    Files.writeString(sourceDir.resolve("android-helper.md"), "# helper\n")
    Files.writeString(
      sourceDir.resolve("addon-manifest.yaml"),
      """
      addon_usage:
        code-review/bill-android-code-review:
          - slug: android-helper
            entrypoint: android-helper.md
      pointers:
        code-review/bill-android-code-review:
          - name: android-helper.md
            target: android-helper.md
      """.trimIndent() + "\n",
    )
    writeConfig(
      home,
      mapOf(
        "external_addon_sources" to listOf(mapOf("path" to sourceDir.toString(), "platform" to "android")),
      ),
    )

    val result = CliRuntime.run(
      listOf("install", "apply-external-addons", "--repo-root", repo.toString()),
      CliRuntimeContext(
        userHome = home,
        environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath(home).toString()),
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "skipped\tandroid")
    assertContains(result.stdout, "not installed")
  }

  @Test
  fun `valid external source applies overlay and reports applied with merged manifest`() {
    val repo = Files.createTempDirectory("ext-addon-apply-happy")
    val packRoot = seedIosPack(repo)
    val home = Files.createTempDirectory("ext-addon-apply-happy-home")
    val sourceDir = seedAcmeSource(home)

    val result = CliRuntime.run(
      listOf("install", "apply-external-addons", "--repo-root", repo.toString()),
      CliRuntimeContext(
        userHome = home,
        environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath(home).toString()),
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "applied\tios")
    assertTrue(
      Files.isRegularFile(packRoot.resolve("addons/acme-review.md")),
      "addon file must be copied into the installed pack. Output:\n${result.stdout}",
    )
    val mergedManifest = Files.readString(packRoot.resolve("platform.yaml"))
    assertContains(mergedManifest, "slug: acme")
    assertContains(mergedManifest, "target: platform-packs/ios/addons/acme-review.md")
  }

  private fun seedIosPack(repo: Path): Path {
    val platformPacksRoot = Files.createDirectories(repo.resolve("platform-packs"))
    val packRoot = Files.createDirectories(platformPacksRoot.resolve("ios"))
    val codeReviewDir = Files.createDirectories(packRoot.resolve("code-review/bill-ios-code-review"))
    Files.writeString(codeReviewDir.resolve("content.md"), "# iOS Code Review\n")
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      platform: ios
      contract_version: "1.1"
      display_name: "iOS"
      routing_signals:
        strong:
          - ".swift"
        tie_breakers: []
      declared_code_review_areas: []
      declared_files:
        baseline: "code-review/bill-ios-code-review/content.md"
      """.trimIndent() + "\n",
    )
    return packRoot
  }

  private fun seedAcmeSource(home: Path): Path {
    val sourceDir = Files.createDirectories(home.resolve("private/ios-acme"))
    Files.writeString(sourceDir.resolve("acme-review.md"), "# acme review body\n")
    Files.writeString(
      sourceDir.resolve("addon-manifest.yaml"),
      """
      addon_usage:
        code-review/bill-ios-code-review:
          - slug: acme
            entrypoint: acme-review.md
      pointers:
        code-review/bill-ios-code-review:
          - name: acme-review.md
            target: acme-review.md
      """.trimIndent() + "\n",
    )
    writeConfig(
      home,
      mapOf(
        "external_addon_sources" to listOf(mapOf("path" to sourceDir.toString(), "platform" to "ios")),
      ),
    )
    return sourceDir
  }

  private fun configPath(home: Path): Path = home.resolve(".skill-bill").resolve("config.json")

  private fun writeConfig(home: Path, payload: Map<String, Any?>) {
    Files.createDirectories(home.resolve(".skill-bill"))
    Files.writeString(configPath(home), JsonSupport.mapToJsonString(payload) + "\n")
  }
}

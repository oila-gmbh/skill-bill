@file:Suppress("MaxLineLength")

package skillbill.cli

import skillbill.application.scaffold.RepoValidationService
import skillbill.application.team.TeamExportException
import skillbill.application.team.TeamExportService
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonSupport
import skillbill.infrastructure.fs.FileSystemTeamExportFileGateway
import skillbill.infrastructure.fs.TeamBundleValidatorAdapter
import skillbill.ports.validation.RepoValidationGateway
import skillbill.ports.validation.model.ReleaseRefMetadata
import skillbill.ports.validation.model.RepoValidationReport
import skillbill.team.model.TeamBundleChannel
import skillbill.team.model.TeamExportRequest
import skillbill.team.model.TeamExportResult
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TeamExportRuntimeTest {
  private val bundleChecksumPlaceholder = "sha256:0000000000000000000000000000000000000000000000000000000000000000"

  @Test
  fun `export is deterministic for unchanged source and fixed metadata`() {
    val repo = writeGovernedRepoFixture()
    val service = teamExportService()
    val first = service.export(exportRequest(repo, outputPath = repo.resolve("dist/first.zip")))
    val second = service.export(exportRequest(repo, outputPath = repo.resolve("dist/second.zip")))

    assertEquals(first.contentHash, second.contentHash)
    assertEquals(first.sourceEntryHashes, second.sourceEntryHashes)
    assertContentEquals(Files.readAllBytes(first.bundlePath), Files.readAllBytes(second.bundlePath))
    assertTrue(first.sourceEntryHashes.any { it.path == "skills/bill-demo/content.md" })
  }

  @Test
  fun `export without explicit output or registry writes default publishable archive`() {
    val repo = writeGovernedRepoFixture()
    val result = teamExportService().export(exportRequest(repo))

    val bundlePath = assertNotNull(result.bundlePath)
    assertEquals(repo.resolve("dist/team-preview-1.2.3.zip"), bundlePath)
    assertTrue(Files.isRegularFile(bundlePath))
  }

  @Test
  fun `direct output writes matching embedded sidecar and checksum metadata`() {
    val repo = writeGovernedRepoFixture()
    val output = repo.resolve("dist/bundle.zip")
    val result = teamExportService().export(exportRequest(repo, outputPath = output))

    assertBundleChecksumConsistency(
      result = result,
      bundlePath = output,
      sidecarPath = output.resolveSibling("bundle.zip.json"),
      checksumPath = output.resolveSibling("bundle.zip.sha256"),
      checksumTarget = "bundle.zip",
    )
  }

  @Test
  fun `export rejects invalid governed source before writing publishable archive`() {
    val repo = writeGovernedRepoFixture()
    val output = repo.resolve("dist/bundle.zip")
    val service = teamExportService(
      validationReport = RepoValidationReport(
        issues = listOf("skills/bill-demo/content.md: invalid frontmatter"),
        skillCount = 1,
        addonCount = 0,
        platformPackCount = 0,
        nativeAgentCount = 0,
      ),
    )

    val error = assertFailsWith<TeamExportException> {
      service.export(exportRequest(repo, outputPath = output))
    }

    assertContains(error.message.orEmpty(), "Repository validation failed")
    assertFalse(Files.exists(output))
  }

  @Test
  fun `registry publish failure leaves no discoverable final directory`() {
    val repo = writeGovernedRepoFixture()
    val registry = Files.createTempDirectory("team-export-registry")
    val service = teamExportService()

    assertFailsWith<TeamExportException> {
      service.export(exportRequest(repo, registryRoot = registry, failAfterRegistryTempWrite = true))
    }

    assertFalse(Files.exists(registry.resolve("preview/1.2.3/team-preview-1.2.3")))
  }

  @Test
  fun `registry publish writes bundle metadata and checksum under final destination`() {
    val repo = writeGovernedRepoFixture()
    val registry = Files.createTempDirectory("team-export-registry")
    val result = teamExportService().export(exportRequest(repo, registryRoot = registry))

    val destination = assertNotNull(result.registryDestination)
    assertTrue(Files.isRegularFile(destination.path.resolve("bundle.zip")))
    assertTrue(Files.isRegularFile(destination.path.resolve("bundle.json")))
    assertBundleChecksumConsistency(
      result = result,
      bundlePath = destination.path.resolve("bundle.zip"),
      sidecarPath = destination.path.resolve("bundle.json"),
      checksumPath = destination.path.resolve("checksum.sha256"),
      checksumTarget = "bundle.zip",
    )
  }

  @Test
  fun `bundle metadata validates through adapter and contains stable source entries`() {
    val repo = writeGovernedRepoFixture()
    val output = repo.resolve("dist/bundle.zip")
    val result = teamExportService().export(exportRequest(repo, outputPath = output))
    val metadata = readBundleMetadata(output)

    TeamBundleValidatorAdapter().validate(metadata, "bundle.json", repo)
    assertEquals(result.contentHash, metadata["content_hash"])
    val sourcePaths = (metadata["sources"] as List<*>).map { source -> (source as Map<*, *>)["path"] }
    assertTrue(sourcePaths.contains("orchestration/contracts/team-bundle-schema.yaml"))
    assertTrue(sourcePaths.contains("skills/bill-demo/content.md"))
  }

  @Test
  fun `export collects core orchestration support sources required by rendered slash commands`() {
    val sourcePaths = FileSystemTeamExportFileGateway().collectSources(repositoryRoot()).map { it.path }

    listOf(
      "orchestration/review-scope/PLAYBOOK.md",
      "orchestration/stack-routing/PLAYBOOK.md",
      "orchestration/review-orchestrator/PLAYBOOK.md",
      "orchestration/review-orchestrator/specialist-contract.md",
      "orchestration/review-delegation/PLAYBOOK.md",
      "orchestration/telemetry-contract/PLAYBOOK.md",
      "orchestration/shell-content-contract/PLAYBOOK.md",
      "orchestration/shell-content-contract/shell-ceremony.md",
      "orchestration/shell-content-contract/peak-hours-warner.md",
      "orchestration/skill-classes/feature-task.yaml",
      "orchestration/skill-classes/code-review-shell.yaml",
      "orchestration/skill-classes/quality-check-shell.yaml",
      "README.md",
      ".agents/skill-overrides.example.md",
    ).forEach { path ->
      assertTrue(sourcePaths.contains(path), "missing exported source $path")
    }
  }

  @Test
  fun `scratch sync installs exported core slash commands and rolls back`() {
    val repo = repositoryRoot()
    val home = Files.createTempDirectory("team-sync-scratch-home")
    val target = home.resolve("codex-skills")
    val firstBundle = home.resolve("bundles/team-1.0.0.zip")
    val secondBundle = home.resolve("bundles/team-2.0.0.zip")

    assertEquals(0, exportBundleFromRepo(repo, firstBundle, "1.0.0").exitCode)
    assertEquals(0, exportBundleFromRepo(repo, secondBundle, "2.0.0").exitCode)

    val firstSync = runTeamSync(home, target, firstBundle)
    assertEquals(0, firstSync.exitCode, firstSync.stdout)
    val secondSync = runTeamSync(home, target, secondBundle)
    assertEquals(0, secondSync.exitCode, secondSync.stdout)

    listOf("bill-feature", "bill-code-review", "bill-code-check").forEach { skill ->
      assertTrue(Files.exists(target.resolve(skill)), "missing installed command $skill")
    }

    val rollback = CliRuntime.run(
      listOf("--home", home.toString(), "team", "rollback") + teamInstallArgs(target) + listOf("--format", "json"),
      CliRuntimeContext(userHome = home, environment = emptyMap()),
    )
    assertEquals(0, rollback.exitCode, rollback.stdout)
    val payload = decodeJsonObject(rollback.stdout)
    val restored = payload["restored"] as Map<*, *>
    val replaced = payload["replaced"] as Map<*, *>
    assertEquals("1.0.0", restored["version"])
    assertEquals("2.0.0", replaced["version"])
    listOf("bill-feature", "bill-code-review", "bill-code-check").forEach { skill ->
      assertTrue(Files.exists(target.resolve(skill)), "missing restored command $skill")
    }
  }

  @Test
  fun `export includes authored platform pack sidecar in metadata archive and result hashes`() {
    val sidecarPath = "platform-packs/kmp/code-review/bill-kmp-code-review-ui/compose-guidelines.md"
    val sidecarText = "# Compose Guidelines\n\nKeep state hoisted.\n"
    val repo = writeGovernedRepoFixtureWithPlatformPackSidecar(sidecarText)
    val output = repo.resolve("dist/bundle.zip")
    val result = teamExportService().export(exportRequest(repo, outputPath = output))
    val metadata = readBundleMetadata(output)
    val metadataSource = (metadata["sources"] as List<*>)
      .map { source -> source as Map<*, *> }
      .first { source -> source["path"] == sidecarPath }
    val expectedHash = sha256(sidecarText.toByteArray(Charsets.UTF_8))

    assertEquals(expectedHash, metadataSource["content_hash"])
    assertEquals(expectedHash, result.sourceEntryHashes.first { it.path == sidecarPath }.contentHash)
    ZipFile(output.toFile()).use { zip ->
      val entry = assertNotNull(zip.getEntry("sources/$sidecarPath"))
      val archivedSidecar = zip.getInputStream(entry).bufferedReader().use { it.readText() }
      assertEquals(sidecarText, archivedSidecar)
    }
  }

  @Test
  fun `team export rejects invalid channel before runtime execution`() {
    val result = CliRuntime.run(
      listOf("team", "export", "--version", "1.2.3", "--channel", "nightly", "--dry-run", "--format", "json"),
      CliRuntimeContext(),
    )

    assertEquals(1, result.exitCode)
    assertContains(result.stdout, "invalid choice")
  }

  @Test
  fun `team export accepts beta channel through cli and schema validation`() {
    val repo = repositoryRoot()
    val result = CliRuntime.run(
      listOf(
        "team",
        "export",
        "--repo-root",
        repo.toString(),
        "--version",
        "1.2.3",
        "--channel",
        "beta",
        "--dry-run",
        "--created-at",
        "2026-01-01T00:00:00Z",
        "--created-by",
        "test",
        "--source-repo",
        "local",
        "--source-ref",
        "refs/heads/test",
        "--format",
        "json",
      ),
      CliRuntimeContext(),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("beta", payload["channel"])
    assertEquals("team-beta-1.2.3", payload["bundle_id"])
  }

  @Test
  fun `team export reports generated source rejection as stable json failure`() {
    val repo = writeGovernedRepoFixture()
    repo.resolve("platform-packs/demo/addons").createDirectories()
    repo.resolve("platform-packs/demo/platform.yaml").writeText(
      """
      platform: demo
      contract_version: "1.2"
      routing_signals:
        strong:
          - ".demo"
      declared_code_review_areas: []
      """.trimIndent() + "\n",
    )
    repo.resolve("platform-packs/demo/addons/content.md").writeText("# Generated addon sibling\n")
    repo.resolve("platform-packs/demo/addons/SKILL.md").writeText("# Generated wrapper\n")

    val result = CliRuntime.run(
      listOf(
        "team",
        "export",
        "--repo-root",
        repo.toString(),
        "--version",
        "1.2.3",
        "--channel",
        "stable",
        "--dry-run",
        "--format",
        "json",
      ),
      CliRuntimeContext(),
    )

    assertEquals(1, result.exitCode)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("failed", payload["status"])
    assertContains(payload["error"].toString(), "committed governed SKILL.md output")
  }

  @Test
  fun `team export rejects generated platform pack skill wrapper selected by broad source collection`() {
    val repo = writeGovernedRepoFixtureWithPlatformPackSidecar("# Compose Guidelines\n")
    repo.resolve("platform-packs/kmp/code-review/bill-kmp-code-review-ui/SKILL.md").writeText("# Generated wrapper\n")

    val result = CliRuntime.run(
      listOf(
        "team",
        "export",
        "--repo-root",
        repo.toString(),
        "--version",
        "1.2.3",
        "--channel",
        "stable",
        "--dry-run",
        "--format",
        "json",
      ),
      CliRuntimeContext(),
    )

    assertEquals(1, result.exitCode)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("failed", payload["status"])
    assertContains(payload["error"].toString(), "committed governed SKILL.md output")
  }

  @Test
  fun `team export dry run emits stable json keys`() {
    val repo = repositoryRoot()
    val result = CliRuntime.run(
      listOf(
        "team",
        "export",
        "--repo-root",
        repo.toString(),
        "--version",
        "1.2.3",
        "--channel",
        "experimental",
        "--dry-run",
        "--created-at",
        "2026-01-01T00:00:00Z",
        "--created-by",
        "test",
        "--source-repo",
        "local",
        "--source-ref",
        "refs/heads/test",
        "--format",
        "json",
      ),
      CliRuntimeContext(),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    assertJsonKeyOrder(
      result.stdout,
      "bundle_path",
      "bundle_id",
      "version",
      "channel",
      "content_hash",
      "checksum",
      "source_ref",
      "validation_summary",
    )
    assertEquals("experimental", payload["channel"])
  }

  private fun teamExportService(
    validationReport: RepoValidationReport = RepoValidationReport(
      issues = emptyList(),
      skillCount = 1,
      addonCount = 0,
      platformPackCount = 0,
      nativeAgentCount = 0,
    ),
  ): TeamExportService = TeamExportService(
    RepoValidationService(FakeRepoValidationGateway(validationReport)),
    TeamBundleValidatorAdapter(),
    FileSystemTeamExportFileGateway(),
    Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
  )

  private fun exportRequest(
    repo: Path,
    outputPath: Path? = null,
    registryRoot: Path? = null,
    failAfterRegistryTempWrite: Boolean = false,
  ): TeamExportRequest = TeamExportRequest(
    repoRoot = repo,
    version = "1.2.3",
    channel = TeamBundleChannel.PREVIEW,
    outputPath = outputPath,
    registryRoot = registryRoot,
    createdAt = "2026-01-01T00:00:00Z",
    createdBy = "test",
    sourceRepo = "local",
    sourceRef = "refs/heads/test",
    failAfterRegistryTempWrite = failAfterRegistryTempWrite,
  )

  private fun writeGovernedRepoFixture(): Path {
    val repo = Files.createTempDirectory("team-export-repo")
    repo.resolve("skills/bill-demo").createDirectories()
    repo.resolve("skills/bill-demo/content.md").writeText(
      """
      ---
      name: bill-demo
      description: Demo skill.
      ---

      # Demo

      Use this governed source.
      """.trimIndent() + "\n",
    )
    repo.resolve("orchestration/contracts").createDirectories()
    repo.resolve("orchestration/contracts/team-bundle-schema.yaml").writeText("contract_version: fixture\n")
    return repo
  }

  private fun writeGovernedRepoFixtureWithPlatformPackSidecar(sidecarText: String): Path {
    val repo = writeGovernedRepoFixture()
    val packRoot = repo.resolve("platform-packs/kmp")
    val skillRoot = packRoot.resolve("code-review/bill-kmp-code-review-ui")
    skillRoot.createDirectories()
    packRoot.resolve("platform.yaml").writeText(
      """
      platform: kmp
      contract_version: "1.2"
      routing_signals:
        strong:
          - "commonMain"
      declared_code_review_areas: []
      """.trimIndent() + "\n",
    )
    skillRoot.resolve("content.md").writeText(
      """
      ---
      name: bill-kmp-code-review-ui
      description: Review KMP UI.
      internal-for: bill-code-review
      ---

      # KMP UI Review

      Read [compose-guidelines.md](compose-guidelines.md).
      """.trimIndent() + "\n",
    )
    skillRoot.resolve("compose-guidelines.md").writeText(sidecarText)
    return repo
  }

  private fun readBundleMetadata(path: Path): Map<String, Any?> = ZipFile(path.toFile()).use { zip ->
    val entry = zip.getEntry("bundle.json")
    val raw = zip.getInputStream(entry).bufferedReader().use { it.readText() }
    decodeJsonObject(raw)
  }

  private fun exportBundleFromRepo(repo: Path, output: Path, version: String) = CliRuntime.run(
    listOf(
      "team",
      "export",
      "--repo-root",
      repo.toString(),
      "--version",
      version,
      "--channel",
      "beta",
      "--output",
      output.toString(),
      "--created-at",
      "2026-01-01T00:00:00Z",
      "--created-by",
      "test",
      "--source-repo",
      "local",
      "--source-ref",
      "refs/heads/test",
      "--format",
      "json",
    ),
    CliRuntimeContext(environment = emptyMap()),
  )

  private fun runTeamSync(home: Path, target: Path, bundle: Path) = CliRuntime.run(
    listOf("--home", home.toString(), "team", "sync", bundle.toString()) +
      teamInstallArgs(target) +
      listOf("--format", "json"),
    CliRuntimeContext(userHome = home, environment = emptyMap()),
  )

  private fun teamInstallArgs(target: Path): List<String> = listOf(
    "--agent-mode",
    "manual",
    "--agent",
    "codex",
    "--agent-target",
    "codex=$target",
    "--platform-mode",
    "none",
    "--telemetry",
    "off",
    "--mcp",
    "skip",
  )

  private fun assertBundleChecksumConsistency(
    result: TeamExportResult,
    bundlePath: Path,
    sidecarPath: Path,
    checksumPath: Path,
    checksumTarget: String,
  ) {
    val embeddedMetadata = readBundleMetadata(bundlePath)
    val sidecarMetadata = decodeJsonObject(Files.readString(sidecarPath))
    val checksumFile = Files.readString(checksumPath)
    val embeddedBundleChecksum = embeddedMetadata["bundle_checksum"]

    assertEquals(embeddedBundleChecksum, sidecarMetadata["bundle_checksum"])
    assertEquals(embeddedBundleChecksum, bundleVerificationChecksum(bundlePath))
    assertEquals(embeddedBundleChecksum, result.checksum)
    assertContains(checksumFile, "${result.checksum}  $checksumTarget")
  }

  private fun bundleVerificationChecksum(path: Path): String {
    val archive = archiveProjectionBytes(path)
    return sha256(archive)
  }

  private fun archiveProjectionBytes(path: Path): ByteArray {
    val entries = ZipFile(path.toFile()).use { zip ->
      zip.entries().asSequence().map { entry ->
        val bytes = if (entry.name == "bundle.json") {
          val metadata = decodeJsonObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
            .toMutableMap()
          metadata["bundle_checksum"] = bundleChecksumPlaceholder
          JsonSupport.mapToJsonString(metadata).toByteArray(Charsets.UTF_8)
        } else {
          zip.getInputStream(entry).use { it.readBytes() }
        }
        entry.name to bytes
      }.toList()
    }
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
      entries.sortedBy { it.first }.forEach { (name, bytes) ->
        zip.putStableEntry(name, bytes)
      }
    }
    return output.toByteArray()
  }

  private fun decodeJsonObject(raw: String): Map<String, Any?> {
    val parsed = JsonSupport.parseObjectOrNull(raw) ?: error("invalid JSON: $raw")
    return requireNotNull(JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed)))
  }

  private fun assertJsonKeyOrder(raw: String, vararg keys: String) {
    val positions = keys.map { key -> raw.indexOf("\"$key\"") }
    assertTrue(positions.all { it >= 0 }, raw)
    assertEquals(positions.sorted(), positions)
  }

  private fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return "sha256:" + digest.joinToString("") { "%02x".format(it) }
  }

  private fun ZipOutputStream.putStableEntry(name: String, bytes: ByteArray) {
    val crc = CRC32().apply { update(bytes) }
    val entry = ZipEntry(name).apply {
      method = ZipEntry.STORED
      size = bytes.size.toLong()
      compressedSize = bytes.size.toLong()
      this.crc = crc.value
      time = 0L
    }
    putNextEntry(entry)
    write(bytes)
    closeEntry()
  }

  private fun repositoryRoot(): Path {
    var current = Path.of("").toAbsolutePath().normalize()
    while (current.parent != null) {
      if (Files.isDirectory(current.resolve("skills")) && Files.isDirectory(current.resolve("runtime-kotlin"))) {
        return current
      }
      current = current.parent
    }
    error("Could not locate repository root from ${Path.of("").toAbsolutePath().normalize()}")
  }
}

private class FakeRepoValidationGateway(
  private val report: RepoValidationReport,
) : RepoValidationGateway {
  override fun validateRepo(repoRoot: Path): RepoValidationReport = report

  override fun parseReleaseRef(rawRef: String): ReleaseRefMetadata =
    ReleaseRefMetadata(rawRef, rawRef, prerelease = false)

  override fun appendGithubOutput(outputPath: Path, metadata: ReleaseRefMetadata) = Unit
}

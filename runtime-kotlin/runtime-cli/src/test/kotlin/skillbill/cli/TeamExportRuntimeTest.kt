@file:Suppress("MaxLineLength")

package skillbill.cli

import skillbill.application.scaffold.RepoValidationService
import skillbill.application.team.TeamExportException
import skillbill.application.team.TeamExportService
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonSupport
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
    val service = teamExportService(validationReport = RepoValidationReport(
      issues = listOf("skills/bill-demo/content.md: invalid frontmatter"),
      skillCount = 1,
      addonCount = 0,
      platformPackCount = 0,
      nativeAgentCount = 0,
    ))

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
    assertEquals(
      listOf(
        "orchestration/contracts/team-bundle-schema.yaml",
        "skills/bill-demo/content.md",
      ),
      (metadata["sources"] as List<*>).map { source -> (source as Map<*, *>)["path"] },
    )
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

  private fun readBundleMetadata(path: Path): Map<String, Any?> =
    ZipFile(path.toFile()).use { zip ->
      val entry = zip.getEntry("bundle.json")
      val raw = zip.getInputStream(entry).bufferedReader().use { it.readText() }
      decodeJsonObject(raw)
    }

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
    assertEquals(result.checksum, sha256(Files.readAllBytes(bundlePath)))
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

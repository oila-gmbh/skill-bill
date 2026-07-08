package skillbill.cli

import skillbill.application.scaffold.RepoValidationService
import skillbill.application.team.TeamExportService
import skillbill.error.GeneratedTeamBundleArtifactEntryError
import skillbill.error.InvalidTeamBundleChecksumError
import skillbill.infrastructure.fs.FileSystemTeamBundleSyncGateway
import skillbill.infrastructure.fs.FileSystemTeamExportFileGateway
import skillbill.infrastructure.fs.TeamBundleValidatorAdapter
import skillbill.ports.team.model.TeamBundleExtractionRequest
import skillbill.ports.team.model.TeamRegistryResolveRequest
import skillbill.ports.validation.RepoValidationGateway
import skillbill.ports.validation.model.ReleaseRefMetadata
import skillbill.ports.validation.model.RepoValidationReport
import skillbill.team.model.TeamBundleChannel
import skillbill.team.model.TeamExportRequest
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TeamSyncGatewayRuntimeTest {
  private val gateway = FileSystemTeamBundleSyncGateway()

  @Test
  fun `readBundle rejects checksum sidecar mismatch`() {
    val bundle = exportBundle("1.0.0")
    Files.writeString(bundle.resolveSibling("${bundle.fileName}.sha256"), "sha256:bad  ${bundle.fileName}\n")

    val error = assertFailsWith<InvalidTeamBundleChecksumError> {
      gateway.readBundle(bundle)
    }

    assertContains(error.message.orEmpty(), "checksum mismatch")
  }

  @Test
  fun `extractCandidate rejects generated artifact entry before writing it`() {
    val bundle = exportBundle("1.0.0")
    val mutated = bundle.resolveSibling("generated-entry.zip")
    appendStoredEntry(bundle, mutated, "sources/skills/bill-demo/SKILL.md", "# Generated\n")
    val candidate = gateway.readBundle(mutated)
    val root = Files.createTempDirectory("team-sync-candidate")

    val error = assertFailsWith<GeneratedTeamBundleArtifactEntryError> {
      gateway.extractCandidate(TeamBundleExtractionRequest(mutated, candidate.bundle, root))
    }

    assertContains(error.message.orEmpty(), "not declared")
  }

  @Test
  fun `resolveLatest selects highest valid bundle for registry channel`() {
    val registry = Files.createTempDirectory("team-sync-registry")
    exportBundle("1.0.0", registry)
    exportBundle("1.10.0", registry)
    exportBundle("1.2.0", registry)

    val selected = gateway.resolveLatest(TeamRegistryResolveRequest(registry, TeamBundleChannel.BETA))

    assertEquals("1.10.0", selected.bundle.metadata.version)
    assertEquals("team-beta-1.10.0", selected.bundle.metadata.bundleId)
  }

  private fun exportBundle(version: String, registryRoot: Path? = null): Path {
    val repo = writeGovernedRepoFixture()
    val result = teamExportService().export(
      TeamExportRequest(
        repoRoot = repo,
        version = version,
        channel = TeamBundleChannel.BETA,
        outputPath = if (registryRoot == null) repo.resolve("dist/bundle-$version.zip") else null,
        registryRoot = registryRoot,
        createdAt = "2026-01-01T00:00:00Z",
        createdBy = "test",
        sourceRepo = "local",
        sourceRef = "refs/heads/test",
      ),
    )
    return requireNotNull(result.bundlePath)
  }

  private fun teamExportService(): TeamExportService = TeamExportService(
    RepoValidationService(FakeTeamSyncRepoValidationGateway()),
    TeamBundleValidatorAdapter(),
    FileSystemTeamExportFileGateway(),
    Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
  )

  private fun writeGovernedRepoFixture(): Path {
    val repo = Files.createTempDirectory("team-sync-export")
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

  private fun appendStoredEntry(source: Path, target: Path, entryName: String, content: String) {
    val entries = ZipFile(source.toFile()).use { zip ->
      zip.entries().asSequence().map { entry ->
        entry.name to zip.getInputStream(entry).use { it.readBytes() }
      }.toList() + (entryName to content.toByteArray(Charsets.UTF_8))
    }
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
      entries.sortedBy { it.first }.forEach { (name, bytes) ->
        zip.putStoredEntry(name, bytes)
      }
    }
    Files.write(target, output.toByteArray())
  }

  private fun ZipOutputStream.putStoredEntry(name: String, bytes: ByteArray) {
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
}

private class FakeTeamSyncRepoValidationGateway : RepoValidationGateway {
  override fun validateRepo(repoRoot: Path): RepoValidationReport = RepoValidationReport(
    issues = emptyList(),
    skillCount = 1,
    addonCount = 0,
    platformPackCount = 0,
    nativeAgentCount = 0,
  )

  override fun parseReleaseRef(rawRef: String): ReleaseRefMetadata =
    ReleaseRefMetadata(rawRef, rawRef, prerelease = false)

  override fun appendGithubOutput(outputPath: Path, metadata: ReleaseRefMetadata) = Unit
}

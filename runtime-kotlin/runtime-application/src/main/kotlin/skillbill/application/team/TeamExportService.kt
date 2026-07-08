@file:Suppress("TooManyFunctions")

package skillbill.application.team

import me.tatarka.inject.annotations.Inject
import skillbill.application.scaffold.RepoValidationService
import skillbill.contracts.JsonSupport
import skillbill.contracts.team.TEAM_BUNDLE_CONTRACT_VERSION
import skillbill.ports.validation.model.RepoValidationReport
import skillbill.team.TeamBundleValidator
import skillbill.team.model.TeamBundleChannel
import skillbill.team.model.TeamBundlePrivacyLevel
import skillbill.team.model.TeamBundleSourceCategory
import skillbill.team.model.TeamExportRegistryDestination
import skillbill.team.model.TeamExportRequest
import skillbill.team.model.TeamExportResult
import skillbill.team.model.TeamExportSourceEntryHash
import skillbill.team.model.TeamExportValidationSummary
import java.io.ByteArrayOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.name
import kotlin.io.path.relativeTo

@Inject
class TeamExportService(
  private val repoValidationService: RepoValidationService,
  private val teamBundleValidator: TeamBundleValidator,
  private val clock: Clock,
) {
  private val bundleChecksumPlaceholder =
    "sha256:0000000000000000000000000000000000000000000000000000000000000000"

  fun export(request: TeamExportRequest): TeamExportResult {
    val repoRoot = request.repoRoot.toAbsolutePath().normalize()
    val validation = repoValidationService.validateRepo(repoRoot)
    if (!validation.passed) {
      throw TeamExportException("Repository validation failed: ${validation.issues.joinToString("; ")}")
    }

    val sources = collectSources(repoRoot)
    val bundleId = "team-${request.channel.wireValue}-${request.version}"
    val outputPath = request.outputPath
      ?: if (request.registryRoot == null) defaultOutputPath(repoRoot, bundleId) else null
    val contentHash = contentHash(request, bundleId, sources)
    val metadataWithPlaceholder = bundleMetadata(request, bundleId, contentHash, bundleChecksumPlaceholder, sources)
    teamBundleValidator.validate(metadataWithPlaceholder, "team-bundle-metadata", repoRoot)
    val metadataChecksum = bundleVerificationChecksum(metadataWithPlaceholder, sources, repoRoot)
    val embeddedMetadata = bundleMetadata(request, bundleId, contentHash, metadataChecksum, sources)
    teamBundleValidator.validate(embeddedMetadata, "team-bundle-metadata", repoRoot)
    val archive = archiveBytes(embeddedMetadata, sources, repoRoot)
    val archiveChecksum = sha256(archive)

    val directBundlePath = if (!request.dryRun) {
      outputPath?.toAbsolutePath()?.normalize()?.also {
        writeDirectBundle(it, archive, embeddedMetadata, archiveChecksum)
      }
    } else {
      null
    }
    val registryDestination = if (!request.dryRun) {
      request.registryRoot?.let {
        publishRegistry(
          registryRoot = it.toAbsolutePath().normalize(),
          request = request,
          bundleId = bundleId,
          archive = archive,
          metadata = embeddedMetadata,
          checksum = archiveChecksum,
        )
      }
    } else {
      null
    }

    return TeamExportResult(
      bundlePath = directBundlePath ?: registryDestination?.path?.resolve("bundle.zip"),
      bundleId = bundleId,
      version = request.version,
      channel = request.channel,
      contentHash = contentHash,
      checksum = archiveChecksum,
      sourceRef = request.sourceRef,
      validationSummary = validation.toSummary(),
      registryDestination = registryDestination,
      sourceEntryHashes = sources.map { TeamExportSourceEntryHash(it.path, it.contentHash) },
    )
  }

  fun defaultCreatedAt(): String = clock.instant().toString()

  private fun collectSources(repoRoot: Path): List<CollectedSource> {
    val candidates = mutableListOf<CollectedSource>()
    collectSkillSources(repoRoot, candidates)
    collectPlatformPackSources(repoRoot, candidates)
    collectOrchestrationSources(repoRoot, candidates)
    val sourceMaps = candidates.distinctBy { it.path }.sortedBy { it.path }.map { source ->
      mapOf("category" to source.category.wireValue, "path" to source.path, "content_hash" to source.contentHash)
    }
    teamBundleValidator.validate(
      minimalValidationBundle(sourceMaps),
      "team-bundle-source-collection",
      repoRoot,
    )
    return candidates.distinctBy { it.path }.sortedBy { it.path }
  }

  private fun defaultOutputPath(repoRoot: Path, bundleId: String): Path =
    repoRoot.resolve("dist").resolve("$bundleId.zip")

  private fun collectSkillSources(repoRoot: Path, candidates: MutableList<CollectedSource>) {
    val skillsRoot = repoRoot.resolve("skills")
    if (!Files.isDirectory(skillsRoot)) return
    Files.list(skillsRoot).use { skills ->
      skills.filter(Files::isDirectory).forEach { skillRoot ->
        addIfRegular(repoRoot, candidates, skillRoot.resolve("content.md"), TeamBundleSourceCategory.HORIZONTAL_SKILL)
        collectNativeAgentSources(repoRoot, candidates, skillRoot)
      }
    }
  }

  private fun collectPlatformPackSources(repoRoot: Path, candidates: MutableList<CollectedSource>) {
    val packsRoot = repoRoot.resolve("platform-packs")
    if (!Files.isDirectory(packsRoot)) return
    Files.list(packsRoot).use { packs ->
      packs.filter(Files::isDirectory).forEach { packRoot ->
        addIfRegular(repoRoot, candidates, packRoot.resolve("platform.yaml"), TeamBundleSourceCategory.PLATFORM_PACK)
        Files.walk(packRoot).use { paths ->
          paths.filter(Files::isRegularFile).forEach { path ->
            val relative = path.relativeTo(packRoot).toString().replace('\\', '/')
            when {
              relative == "platform.yaml" -> Unit
              relative == "content.md" || relative.endsWith("/content.md") ->
                addIfRegular(repoRoot, candidates, path, TeamBundleSourceCategory.PLATFORM_PACK)
              relative.startsWith("addons/") ->
                addIfRegular(repoRoot, candidates, path, TeamBundleSourceCategory.ADDON)
              relative.contains("/native-agents/") ->
                addIfRegular(repoRoot, candidates, path, TeamBundleSourceCategory.NATIVE_AGENT_SOURCE)
            }
          }
        }
      }
    }
  }

  private fun collectNativeAgentSources(repoRoot: Path, candidates: MutableList<CollectedSource>, skillRoot: Path) {
    val nativeAgents = skillRoot.resolve("native-agents")
    if (!Files.isDirectory(nativeAgents)) return
    Files.walk(nativeAgents).use { paths ->
      paths.filter(Files::isRegularFile).forEach { path ->
        addIfRegular(repoRoot, candidates, path, TeamBundleSourceCategory.NATIVE_AGENT_SOURCE)
      }
    }
  }

  private fun collectOrchestrationSources(repoRoot: Path, candidates: MutableList<CollectedSource>) {
    addIfRegular(
      repoRoot,
      candidates,
      repoRoot.resolve("orchestration/contracts/team-bundle-schema.yaml"),
      TeamBundleSourceCategory.ORCHESTRATION_CONTRACT_OR_SUPPORT,
    )
  }

  private fun addIfRegular(
    repoRoot: Path,
    candidates: MutableList<CollectedSource>,
    path: Path,
    category: TeamBundleSourceCategory,
  ) {
    if (!Files.isRegularFile(path)) return
    val relative = path.toAbsolutePath().normalize().relativeTo(repoRoot).toString().replace('\\', '/')
    candidates += CollectedSource(category = category, path = relative, contentHash = sha256(Files.readAllBytes(path)))
  }

  private fun bundleMetadata(
    request: TeamExportRequest,
    bundleId: String,
    contentHash: String,
    bundleChecksum: String,
    sources: List<CollectedSource>,
  ): Map<String, Any?> = linkedMapOf(
    "contract_version" to TEAM_BUNDLE_CONTRACT_VERSION,
    "bundle_id" to bundleId,
    "version" to request.version,
    "channel" to request.channel.wireValue,
    "created_at" to request.createdAt,
    "created_by" to request.createdBy,
    "source_repo" to request.sourceRepo,
    "source_ref" to request.sourceRef,
    "source_commit" to request.sourceCommit,
    "content_hash" to contentHash,
    "manifest_hashes" to manifestHashes(sources),
    "bundle_checksum" to bundleChecksum,
    "sources" to sources.map { source ->
      linkedMapOf(
        "category" to source.category.wireValue,
        "path" to source.path,
        "content_hash" to source.contentHash,
      )
    },
    "compatibility" to linkedMapOf(
      "min_skill_bill_version" to request.minSkillBillVersion,
      "shell_contract_version" to request.shellContractVersion,
      "platform_pack_contract_version" to request.platformPackContractVersion,
    ),
    "telemetry_defaults" to linkedMapOf(
      "enabled" to false,
      "level" to TeamBundlePrivacyLevel.ANONYMOUS.wireValue,
    ),
    "privacy_defaults" to linkedMapOf(
      "telemetry" to TeamBundlePrivacyLevel.ANONYMOUS.wireValue,
      "source_paths" to TeamBundlePrivacyLevel.ANONYMOUS.wireValue,
      "author_identity" to TeamBundlePrivacyLevel.OFF.wireValue,
    ),
    "exclusions" to linkedMapOf(
      "paths" to emptyList<String>(),
      "reasons" to emptyMap<String, String>(),
    ),
  )

  private fun manifestHashes(sources: List<CollectedSource>): Map<String, String> {
    val manifests = sources.filter { it.path.endsWith("platform.yaml") || it.path.endsWith("content.md") }
    val selected = if (manifests.isNotEmpty()) manifests else sources
    return selected.sortedBy { it.path }.associate { it.path to it.contentHash }
  }

  private fun contentHash(request: TeamExportRequest, bundleId: String, sources: List<CollectedSource>): String {
    val canonical = buildString {
      appendLine("bundle_id=$bundleId")
      appendLine("version=${request.version}")
      appendLine("channel=${request.channel.wireValue}")
      appendLine("created_at=${request.createdAt}")
      appendLine("created_by=${request.createdBy}")
      appendLine("source_repo=${request.sourceRepo}")
      appendLine("source_ref=${request.sourceRef}")
      appendLine("source_commit=${request.sourceCommit.orEmpty()}")
      sources.sortedBy { it.path }.forEach { source ->
        append(source.path).append('\u0000').append(source.contentHash).append('\n')
      }
    }
    return sha256(canonical.toByteArray(Charsets.UTF_8))
  }

  private fun archiveBytes(metadata: Map<String, Any?>, sources: List<CollectedSource>, repoRoot: Path): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
      zip.putStableEntry("bundle.json", JsonSupport.mapToJsonString(metadata).toByteArray(Charsets.UTF_8))
      sources.sortedBy { it.path }.forEach { source ->
        zip.putStableEntry("sources/${source.path}", Files.readAllBytes(repoRoot.resolve(source.path)))
      }
    }
    return output.toByteArray()
  }

  private fun bundleVerificationChecksum(
    metadataWithPlaceholder: Map<String, Any?>,
    sources: List<CollectedSource>,
    repoRoot: Path,
  ): String = sha256(archiveBytes(metadataWithPlaceholder, sources, repoRoot))

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

  private fun writeDirectBundle(
    path: Path,
    archive: ByteArray,
    metadata: Map<String, Any?>,
    checksum: String,
  ) {
    writeFileAtomically(path, archive)
    writeStringAtomically(path.resolveSibling("${path.name}.json"), JsonSupport.mapToJsonString(metadata) + "\n")
    writeStringAtomically(path.resolveSibling("${path.name}.sha256"), "$checksum  ${path.name}\n")
  }

  private fun writeStringAtomically(path: Path, content: String) {
    writeFileAtomically(path, content.toByteArray(Charsets.UTF_8))
  }

  private fun writeFileAtomically(path: Path, bytes: ByteArray) {
    Files.createDirectories(path.parent ?: Path.of("."))
    val temp = Files.createTempFile(path.parent ?: Path.of("."), ".${path.name}.", ".tmp")
    try {
      Files.write(temp, bytes)
      moveFileAtomically(temp, path)
    } finally {
      Files.deleteIfExists(temp)
    }
  }

  private fun publishRegistry(
    registryRoot: Path,
    request: TeamExportRequest,
    bundleId: String,
    archive: ByteArray,
    metadata: Map<String, Any?>,
    checksum: String,
  ): TeamExportRegistryDestination {
    val finalDir = registryRoot.resolve(request.channel.wireValue).resolve(request.version).resolve(bundleId)
    if (Files.exists(finalDir)) {
      throw TeamExportException("Registry destination already exists: $finalDir")
    }
    Files.createDirectories(finalDir.parent)
    val tempDir = Files.createTempDirectory(registryRoot, ".team-export-$bundleId-")
    try {
      Files.write(tempDir.resolve("bundle.zip"), archive)
      Files.writeString(tempDir.resolve("bundle.json"), JsonSupport.mapToJsonString(metadata) + "\n")
      Files.writeString(tempDir.resolve("checksum.sha256"), "$checksum  bundle.zip\n")
      if (request.failAfterRegistryTempWrite) {
        throw TeamExportException("Injected registry publish failure.")
      }
      moveDirectoryAtomically(tempDir, finalDir)
      return TeamExportRegistryDestination(finalDir, request.channel.wireValue, request.version, bundleId)
    } catch (error: Exception) {
      deleteRecursively(tempDir)
      throw error
    }
  }

  private fun moveFileAtomically(source: Path, target: Path) {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(source, target)
    }
  }

  private fun moveDirectoryAtomically(source: Path, target: Path) {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (error: AtomicMoveNotSupportedException) {
      throw TeamExportException("Atomic registry publish is not supported for $target.", error)
    }
  }

  private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }

  private fun minimalValidationBundle(sources: List<Map<String, String>>): Map<String, Any?> = linkedMapOf(
    "contract_version" to TEAM_BUNDLE_CONTRACT_VERSION,
    "bundle_id" to "team-source-validation",
    "version" to "0.0.0",
    "channel" to TeamBundleChannel.EXPERIMENTAL.wireValue,
    "created_at" to "1970-01-01T00:00:00Z",
    "created_by" to "skill-bill",
    "source_repo" to "local",
    "source_ref" to "HEAD",
    "content_hash" to sha256(ByteArray(0)),
    "manifest_hashes" to mapOf("source_index" to sha256(ByteArray(0))),
    "bundle_checksum" to sha256(ByteArray(0)),
    "sources" to sources,
    "compatibility" to mapOf("min_skill_bill_version" to "0.1", "shell_contract_version" to "1.2"),
    "telemetry_defaults" to mapOf("enabled" to false, "level" to "anonymous"),
    "privacy_defaults" to mapOf("telemetry" to "anonymous", "source_paths" to "anonymous", "author_identity" to "off"),
    "exclusions" to mapOf("paths" to emptyList<String>(), "reasons" to emptyMap<String, String>()),
  )

  private fun RepoValidationReport.toSummary(): TeamExportValidationSummary = TeamExportValidationSummary(
    status = if (passed) "passed" else "failed",
    skillCount = skillCount,
    addonCount = addonCount,
    platformPackCount = platformPackCount,
    nativeAgentCount = nativeAgentCount,
  )
}

class TeamExportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private data class CollectedSource(
  val category: TeamBundleSourceCategory,
  val path: String,
  val contentHash: String,
)

private fun sha256(bytes: ByteArray): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
  return "sha256:" + digest.joinToString("") { "%02x".format(it) }
}

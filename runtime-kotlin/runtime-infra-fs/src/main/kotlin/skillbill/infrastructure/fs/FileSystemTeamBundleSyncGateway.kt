@file:Suppress("TooManyFunctions")

package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonSupport
import skillbill.contracts.team.TeamBundleSchemaValidator
import skillbill.error.GeneratedTeamBundleArtifactEntryError
import skillbill.error.InvalidTeamBundleChecksumError
import skillbill.error.MissingPreviousTeamBundleSourceError
import skillbill.error.TeamBundleContentHashMismatchError
import skillbill.ports.team.TeamBundleArchiveGateway
import skillbill.ports.team.TeamBundleGatewayException
import skillbill.ports.team.TeamBundleRegistryResolver
import skillbill.ports.team.TeamBundleStatePersistence
import skillbill.ports.team.model.TeamBundleCandidate
import skillbill.ports.team.model.TeamBundleExtractionRequest
import skillbill.ports.team.model.TeamBundleStateReadRequest
import skillbill.ports.team.model.TeamBundleStateWriteRequest
import skillbill.ports.team.model.TeamRegistryResolveRequest
import skillbill.team.model.InstalledTeamBundleRecord
import skillbill.team.model.TeamBundle
import skillbill.team.model.TeamBundleContentHashInput
import skillbill.team.model.TeamBundleHashing
import skillbill.team.model.TeamBundleParser
import skillbill.team.model.TeamBundleSourceEntry
import skillbill.team.model.TeamBundleSourceHash
import skillbill.team.model.TeamBundleVerificationSummary
import skillbill.team.model.TeamSyncSourceKind
import java.io.ByteArrayOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.name

@Inject
class FileSystemTeamBundleSyncGateway :
  TeamBundleArchiveGateway,
  TeamBundleRegistryResolver,
  TeamBundleStatePersistence {
  override fun readBundle(path: Path): TeamBundleCandidate {
    val archivePath = path.toAbsolutePath().normalize()
    if (!Files.isRegularFile(archivePath)) {
      throw MissingPreviousTeamBundleSourceError(archivePath.toString())
    }
    val metadata = readMetadata(archivePath)
    TeamBundleSchemaValidator.validate(metadata, archivePath.toString())
    val bundle = TeamBundleParser.parse(metadata, archivePath.toString())
    verifyArchiveChecksum(archivePath, metadata, bundle)
    return TeamBundleCandidate(
      archivePath = archivePath,
      metadata = metadata,
      bundle = bundle,
      checksum = bundle.hashes.bundleChecksum,
    )
  }

  override fun extractCandidate(request: TeamBundleExtractionRequest): Path {
    val root = request.candidateRoot.toAbsolutePath().normalize()
    if (Files.exists(root)) {
      deleteRecursively(root)
    }
    Files.createDirectories(root)
    val declaredSourceEntries = request.bundle.sources.associateBy { source -> "sources/${source.path}" }
    val seen = mutableSetOf<String>()
    ZipFile(request.archivePath.toFile()).use { zip ->
      val entries = zip.entries().asSequence().toList()
      entries.forEach { entry ->
        val normalized = normalizedEntryName(entry.name)
        require(seen.add(normalized)) { "Duplicate archive entry: $normalized" }
        validateEntryName(normalized, declaredSourceEntries)
      }
      require(seen.contains("bundle.json")) { "Team bundle archive is missing bundle.json." }
      val missing = declaredSourceEntries.keys - seen
      require(missing.isEmpty()) {
        "Team bundle archive is missing declared source(s): ${missing.sorted().joinToString(", ")}."
      }
      entries.filterNot(ZipEntry::isDirectory).forEach { entry ->
        val normalized = normalizedEntryName(entry.name)
        if (normalized == "bundle.json") return@forEach
        val target = root.resolve(normalized.removePrefix("sources/")).normalize()
        require(target.startsWith(root)) { "Archive entry escapes candidate root: $normalized" }
        Files.createDirectories(target.parent)
        zip.getInputStream(entry).use { input -> Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING) }
      }
    }
    return root
  }

  override fun verifyExtractedSources(bundle: TeamBundle, sourceRoot: Path): TeamBundleVerificationSummary {
    bundle.sources.forEach { source ->
      val actual = TeamBundleHashing.sha256(Files.readAllBytes(sourceRoot.resolve(source.path)))
      if (actual != source.contentHash) {
        throw TeamBundleContentHashMismatchError(bundle.metadata.bundleId, source.contentHash, actual)
      }
    }
    bundle.hashes.manifestHashes.forEach { (path, expected) ->
      val actual = TeamBundleHashing.sha256(Files.readAllBytes(sourceRoot.resolve(path)))
      if (actual != expected) {
        throw TeamBundleContentHashMismatchError(bundle.metadata.bundleId, expected, actual)
      }
    }
    val computedContentHash = TeamBundleHashing.contentHash(
      TeamBundleContentHashInput(
        bundleId = bundle.metadata.bundleId,
        version = bundle.metadata.version,
        channel = bundle.metadata.channel,
        createdAt = bundle.metadata.createdAt,
        createdBy = bundle.metadata.createdBy,
        sourceRepo = bundle.metadata.sourceRepo,
        sourceRef = bundle.metadata.sourceRef,
        sourceCommit = bundle.metadata.sourceCommit,
        sources = bundle.sources.map { source -> TeamBundleSourceHash(source.path, source.contentHash) },
      ),
    )
    if (computedContentHash != bundle.hashes.contentHash) {
      throw TeamBundleContentHashMismatchError(bundle.metadata.bundleId, bundle.hashes.contentHash, computedContentHash)
    }
    return TeamBundleVerificationSummary(
      checksum = bundle.hashes.bundleChecksum,
      contentHash = bundle.hashes.contentHash,
      sourceCount = bundle.sources.size,
      manifestCount = bundle.hashes.manifestHashes.size,
      sourceRoot = sourceRoot,
    )
  }

  override fun cacheBundle(source: Path, home: Path, bundleId: String, checksum: String): Path {
    val safeChecksum = checksum.removePrefix("sha256:").take(SHORT_HASH_LENGTH)
    val target = stateRoot(home).resolve("bundles").resolve("$bundleId-$safeChecksum.zip")
    Files.createDirectories(target.parent)
    writeFileAtomically(target, Files.readAllBytes(source))
    return target
  }

  override fun resolveLatest(request: TeamRegistryResolveRequest): TeamBundleCandidate {
    val channelRoot = request.registryRoot.toAbsolutePath().normalize().resolve(request.channel.wireValue)
    if (!Files.isDirectory(channelRoot)) {
      throw TeamBundleGatewayException("Team bundle registry channel does not exist: $channelRoot")
    }
    val candidates = Files.walk(channelRoot).use { paths ->
      paths
        .filter { path -> path.name == "bundle.zip" && Files.isRegularFile(path) }
        .map { path -> runCatching { readBundle(path) }.getOrNull() }
        .filter { candidate -> candidate != null && candidate.bundle.metadata.channel == request.channel }
        .map { candidate -> requireNotNull(candidate) }
        .toList()
    }
    if (candidates.isEmpty()) {
      throw TeamBundleGatewayException("No valid ${request.channel.wireValue} team bundle found in $channelRoot.")
    }
    return candidates.maxWith(
      compareBy<TeamBundleCandidate> { versionKey(it.bundle.metadata.version) }
        .thenBy { it.bundle.metadata.bundleId }
        .thenBy { it.archivePath.toString() },
    )
  }

  override fun read(request: TeamBundleStateReadRequest): InstalledTeamBundleRecord? {
    val path = statePath(request.home)
    if (!Files.isRegularFile(path)) return null
    val raw = JsonSupport.parseObjectOrNull(Files.readString(path))
      ?: throw TeamBundleGatewayException("Team bundle state is malformed at $path.")
    return recordFromPayload(JsonSupport.jsonElementToValue(raw) as Map<*, *>)
  }

  override fun write(request: TeamBundleStateWriteRequest) {
    writeStringAtomically(statePath(request.home), JsonSupport.mapToJsonString(request.record.toPayload()) + "\n")
  }

  private fun readMetadata(archivePath: Path): Map<String, Any?> = ZipFile(archivePath.toFile()).use { zip ->
    val entry = zip.getEntry("bundle.json")
      ?: throw TeamBundleGatewayException("Team bundle archive is missing bundle.json.")
    val raw = zip.getInputStream(entry).bufferedReader().use { it.readText() }
    JsonSupport.parseObjectOrNull(raw)
      ?.let { JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(it)) }
      ?: throw TeamBundleGatewayException("Team bundle metadata is malformed in $archivePath.")
  }

  private fun verifyArchiveChecksum(archivePath: Path, metadata: Map<String, Any?>, bundle: TeamBundle) {
    val placeholderMetadata = metadata + ("bundle_checksum" to TeamBundleHashing.BUNDLE_CHECKSUM_PLACEHOLDER)
    val archiveChecksum = TeamBundleHashing.sha256(
      rebuildArchiveBytes(archivePath, placeholderMetadata, bundle.sources),
    )
    if (archiveChecksum != bundle.hashes.bundleChecksum) {
      throw InvalidTeamBundleChecksumError(archivePath.toString(), bundle.hashes.bundleChecksum, archiveChecksum)
    }
    expectedChecksumFromSidecar(archivePath)?.let { expected ->
      if (expected != bundle.hashes.bundleChecksum) {
        throw InvalidTeamBundleChecksumError(archivePath.toString(), expected, bundle.hashes.bundleChecksum)
      }
    }
  }

  private fun rebuildArchiveBytes(
    archivePath: Path,
    metadata: Map<String, Any?>,
    sources: List<TeamBundleSourceEntry>,
  ): ByteArray {
    val output = ByteArrayOutputStream()
    ZipFile(archivePath.toFile()).use { zip ->
      ZipOutputStream(output).use { rebuilt ->
        rebuilt.putStableEntry("bundle.json", JsonSupport.mapToJsonString(metadata).toByteArray(Charsets.UTF_8))
        sources.sortedBy { source -> source.path }.forEach { source ->
          val entry = zip.getEntry("sources/${source.path}")
            ?: throw TeamBundleGatewayException("Team bundle archive is missing declared source: ${source.path}")
          rebuilt.putStableEntry("sources/${source.path}", zip.getInputStream(entry).use { it.readBytes() })
        }
      }
    }
    return output.toByteArray()
  }

  private fun expectedChecksumFromSidecar(archivePath: Path): String? {
    val direct = archivePath.resolveSibling("${archivePath.name}.sha256")
    val registry = archivePath.resolveSibling("checksum.sha256")
    val sidecar = listOf(direct, registry).firstOrNull(Files::isRegularFile) ?: return null
    return Files.readString(sidecar).trim().split(Regex("\\s+")).firstOrNull()
  }

  private fun validateEntryName(normalized: String, declaredSources: Map<String, TeamBundleSourceEntry>) {
    if (normalized == "bundle.json") return
    if (!normalized.startsWith("sources/")) {
      invalidBundleEntry(normalized, "only bundle.json and declared sources/ entries are allowed.")
    }
    val sourcePath = normalized.removePrefix("sources/")
    if (declaredSources[normalized] == null) {
      invalidBundleEntry(normalized, "entry is not declared in bundle.json sources.")
    }
    val segments = sourcePath.split('/')
    when {
      sourcePath.endsWith("/SKILL.md") || sourcePath == "SKILL.md" ->
        invalidBundleEntry(normalized, "generated governed SKILL.md wrappers are forbidden.")
      segments.any { it in providerNativeOutputDirectories } ->
        invalidBundleEntry(normalized, "provider-native generated output is forbidden.")
      segments.any { it in forbiddenStateOrInstallSegments } ->
        invalidBundleEntry(normalized, "runtime state and install staging artifacts are forbidden.")
    }
  }

  private fun normalizedEntryName(name: String): String {
    val normalized = Path.of(name).normalize().toString().replace('\\', '/')
    require(isSafeEntryName(name, normalized)) {
      "Archive entry escapes the bundle root: $name"
    }
    return normalized
  }

  private fun statePath(home: Path): Path = stateRoot(home).resolve("team-bundle-state.json")

  private fun stateRoot(home: Path): Path = home.toAbsolutePath().normalize().resolve(".skill-bill")

  private fun recordFromPayload(raw: Map<*, *>): InstalledTeamBundleRecord = InstalledTeamBundleRecord(
    bundleId = raw.requiredString("bundle_id"),
    version = raw.requiredString("version"),
    channel = skillbill.team.model.TeamBundleChannel.fromWireValue(raw.requiredString("channel"))
      ?: throw TeamBundleGatewayException("Team bundle state has unknown channel."),
    contentHash = raw.requiredString("content_hash"),
    checksum = raw.requiredString("checksum"),
    sourceRef = raw.requiredString("source_ref"),
    sourceRepo = raw.requiredString("source_repo"),
    sourceCommit = raw["source_commit"] as? String,
    installedAt = raw.requiredString("installed_at"),
    archivePath = Path.of(raw.requiredString("archive_path")),
    sourceKind = TeamSyncSourceKind.valueOf(raw.requiredString("source_kind").uppercase()),
    previous = (raw["previous"] as? Map<*, *>)?.let(::recordFromPayload),
  )

  private fun Map<*, *>.requiredString(key: String): String = this[key] as? String
    ?: throw TeamBundleGatewayException("Team bundle state is missing string field '$key'.")

  private val providerNativeOutputDirectories = setOf(
    "claude-agents",
    "codex-agents",
    "opencode-agents",
    "junie-agents",
  )
  private val forbiddenStateOrInstallSegments = setOf(
    ".skill-bill",
    "staging",
    "installed",
    "install-staging",
    "workflow",
    "workflows",
    "runtime-desktop-state",
    "desktop-state",
    "telemetry-outbox",
    "outbox",
  )
}

private const val SHORT_HASH_LENGTH = 16
private const val VERSION_PADDING_LENGTH = 10

private fun invalidBundleEntry(normalized: String, reason: String): Nothing =
  throw GeneratedTeamBundleArtifactEntryError(normalized, reason)

private fun isSafeEntryName(name: String, normalized: String): Boolean = normalized.isNotBlank() &&
  !normalized.startsWith("../") &&
  normalized != ".." &&
  !Path.of(name).isAbsolute

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

private fun writeStringAtomically(path: Path, content: String) {
  writeFileAtomically(path, content.toByteArray(Charsets.UTF_8))
}

private fun writeFileAtomically(path: Path, bytes: ByteArray) {
  Files.createDirectories(path.parent ?: Path.of("."))
  val temp = Files.createTempFile(path.parent ?: Path.of("."), ".${path.name}.", ".tmp")
  try {
    Files.write(temp, bytes)
    try {
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
    }
  } finally {
    Files.deleteIfExists(temp)
  }
}

private fun deleteRecursively(path: Path) {
  if (!Files.exists(path)) return
  Files.walk(path).use { paths ->
    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
  }
}

private fun versionKey(version: String): String = version
  .split('.', '-', '_')
  .joinToString(".") { part -> part.toIntOrNull()?.toString()?.padStart(VERSION_PADDING_LENGTH, '0') ?: part }

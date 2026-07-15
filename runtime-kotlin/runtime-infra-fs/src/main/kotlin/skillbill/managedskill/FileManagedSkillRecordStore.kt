package skillbill.managedskill

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import skillbill.contracts.managedskill.ManagedSkillRecordSchemaValidator
import skillbill.error.InvalidManagedSkillRecordSchemaError
import skillbill.managedskill.model.AgentSkillTargetId
import skillbill.managedskill.model.ManagedSkillRecord
import skillbill.managedskill.model.ManagedSkillSourceKind
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.time.Instant

class FileManagedSkillRecordStore private constructor(
  stateRoot: Path,
  private val atomicReplace: ((Path, Path) -> Unit)?,
  private val forceDirectory: (Path) -> Unit = { directory ->
    FileChannel.open(directory, READ).use { it.force(true) }
  },
  @Suppress("UNUSED_PARAMETER") publicationTestSeam: Unit,
) {
  constructor(
    stateRoot: Path,
    forceDirectory: (Path) -> Unit = { directory ->
      FileChannel.open(directory, READ).use { it.force(true) }
    },
  ) : this(stateRoot, null, forceDirectory, Unit)

  internal constructor(
    stateRoot: Path,
    atomicReplace: (Path, Path) -> Unit,
    forceDirectory: (Path) -> Unit = { directory ->
      FileChannel.open(directory, READ).use { it.force(true) }
    },
  ) : this(stateRoot, atomicReplace, forceDirectory, Unit)

  companion object {
    const val EXPECTED_ABSENT = "absent"
    private const val MAX_RECORD_BYTES = 1024 * 1024
  }
  private val stateRoot = stateRoot.toAbsolutePath().normalize()
  private val stateRootIdentity = Files.readAttributes(
    this.stateRoot,
    java.nio.file.attribute.BasicFileAttributes::class.java,
    NOFOLLOW_LINKS,
  ).also {
    if (!it.isDirectory || it.isSymbolicLink) {
      throw InvalidManagedSkillRecordSchemaError(this.stateRoot.toString(), "managed state root is not a real directory")
    }
  }.fileKey()
  private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    .enable(SerializationFeature.INDENT_OUTPUT)

  fun recordPath(name: String): Path = confined("managed-skills", safeName(name), "record.json")
  fun sourceRoot(name: String): Path = confined("managed-skills", safeName(name), "source")
  fun snapshotRoot(name: String, contentHash: String): Path =
    confined("installed-skills", "${safeName(name)}-${safeHash(contentHash)}")

  fun read(name: String): ManagedSkillRecord {
    val safeName = safeName(name)
    return readPath(recordPath(safeName), safeName)
  }

  fun readPath(path: Path): ManagedSkillRecord {
    val confinedPath = requireConfined(path)
    val managedRoot = stateRoot.resolve("managed-skills")
    val relative = try { managedRoot.relativize(confinedPath) } catch (error: IllegalArgumentException) {
      throw InvalidManagedSkillRecordSchemaError(path.toString(), "record path is outside managed-skills", error)
    }
    if (!confinedPath.startsWith(managedRoot) || relative.nameCount != 2 || relative.fileName.toString() != "record.json") {
      throw InvalidManagedSkillRecordSchemaError(path.toString(), "record path must be managed-skills/<name>/record.json")
    }
    return readPath(confinedPath, safeName(relative.getName(0).toString()))
  }

  private fun readPath(path: Path, expectedName: String?): ManagedSkillRecord {
    val confinedPath = requireConfined(path)
    requireRealAncestors(confinedPath.parent)
    val name = expectedName ?: safeName(confinedPath.parent.fileName.toString())
    return withRecordDirectory(name) { directory, displayDirectory ->
      readValidated(directory, displayDirectory.resolve("record.json"), name).first
    }
  }

  private fun readValidated(
    directory: RecordDirectory,
    path: Path,
    expectedName: String,
  ): Pair<ManagedSkillRecord, ByteArray> {
    val confinedPath = requireConfined(path)
    val raw = try {
      readNoFollow(directory, Path.of("record.json"), confinedPath).let { bytes ->
        mapper.readValue(bytes, object : TypeReference<Map<String, Any?>>() {}) to bytes
      }
    } catch (error: InvalidManagedSkillRecordSchemaError) {
      throw error
    } catch (error: Exception) {
      throw InvalidManagedSkillRecordSchemaError(confinedPath.toString(), "record is unreadable JSON", error)
    }
    ManagedSkillRecordSchemaValidator.validate(raw.first, confinedPath.toString())
    val record = mapRecord(raw.first, confinedPath)
    validateRecordPaths(record, confinedPath)
    if (record.name != expectedName) {
      throw InvalidManagedSkillRecordSchemaError(confinedPath.toString(), "record name does not match its managed directory")
    }
    return record to raw.second
  }

  fun write(record: ManagedSkillRecord, expectedDigest: String? = null) {
    val name = safeName(record.name)
    val path = recordPath(name)
    val raw = record.toWire()
    ManagedSkillRecordSchemaValidator.validate(raw, path.toString())
    validateRecordPaths(record, path)
    createConfinedDirectories(path.parent)
    requireRealAncestors(path.parent)
    withRecordDirectory(name) { directory, displayDirectory ->
      val lockChannel = directory.newByteChannel(Path.of(".record.lock"), setOf(CREATE, WRITE, NOFOLLOW_LINKS))
      lockChannel.use { channel ->
        val fileChannel = channel as? FileChannel
          ?: throw IllegalStateException("Managed-record locking requires a file-channel-backed provider.")
        fileChannel.lock().use {
          val currentDigest = if (exists(directory, Path.of("record.json"))) {
            digest(readValidated(directory, path, name).second)
          } else null
          if (expectedDigest != null && expectedDigest != currentDigest &&
            !(expectedDigest == EXPECTED_ABSENT && currentDigest == null)
          ) throw IllegalStateException("Managed skill record changed after preview.")
          publish(directory, displayDirectory, mapper.writeValueAsBytes(raw))
        }
      }
    }
  }

  fun digest(name: String): String? {
    val path = recordPath(name)
    requireRealAncestors(path.parent)
    val safeName = safeName(name)
    return withRecordDirectory(safeName) { directory, _ ->
      if (exists(directory, Path.of("record.json"))) digest(readValidated(directory, path, safeName).second) else null
    }
  }

  private fun publish(directory: RecordDirectory, displayDirectory: Path, bytes: ByteArray) {
    val temporaryName = Path.of(".record-${java.util.UUID.randomUUID()}.json")
    val temporary = displayDirectory.resolve(temporaryName)
    try {
      directory.newByteChannel(temporaryName, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)).use {
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) it.write(buffer)
        (it as? FileChannel)?.force(true)
      }
      try {
        atomicReplace?.invoke(temporary, displayDirectory.resolve("record.json"))
          ?: directory.atomicMove(temporaryName, Path.of("record.json"))
        forceDirectory(displayDirectory)
      } catch (error: Exception) {
        throw IllegalStateException("Atomic managed-record publication is unavailable.", error)
      }
    } finally {
      try {
        directory.deleteFile(temporaryName)
      } catch (_: java.nio.file.NoSuchFileException) {
      }
    }
  }

  private fun mapRecord(raw: Map<String, Any?>, path: Path): ManagedSkillRecord = try {
    @Suppress("UNCHECKED_CAST")
    val targets = (raw.getValue("selected_targets") as List<Map<String, String>>).mapTo(linkedSetOf()) {
      val target = Path.of(it.getValue("skills_path"))
      if (!it.getValue("provider").matches(Regex("^[a-z0-9][a-z0-9-]{0,62}$"))) {
        throw IllegalArgumentException("provider must be a stable safe identifier")
      }
      if (!target.isAbsolute || target != target.normalize()) throw IllegalArgumentException("target path must be absolute and normalized")
      AgentSkillTargetId(it.getValue("provider"), target)
    }
    if (targets.size != (raw.getValue("selected_targets") as List<*>).size) {
      throw IllegalArgumentException("selected targets contain duplicate canonical identities")
    }
    ManagedSkillRecord(
      name = raw.getValue("name") as String,
      sourceKind = ManagedSkillSourceKind.valueOf((raw.getValue("source_kind") as String).uppercase()),
      sourcePath = Path.of(raw.getValue("source_path") as String).also {
        if (!it.isAbsolute || it != it.normalize()) throw IllegalArgumentException("source path must be absolute and normalized")
      },
      activeContentHash = raw.getValue("active_content_hash") as String,
      selectedTargets = targets,
      importedAt = Instant.parse(raw.getValue("imported_at") as String),
      updatedAt = Instant.parse(raw.getValue("updated_at") as String),
      contractVersion = raw.getValue("contract_version") as String,
    )
  } catch (error: Exception) {
    throw InvalidManagedSkillRecordSchemaError(path.toString(), "record values cannot be mapped", error)
  }

  private fun validateRecordPaths(record: ManagedSkillRecord, recordPath: Path) {
    if (!record.sourcePath.isAbsolute || record.sourcePath != record.sourcePath.normalize()) {
      throw InvalidManagedSkillRecordSchemaError(recordPath.toString(), "source_path must be absolute and normalized")
    }
    if (record.sourcePath != sourceRoot(record.name)) {
      throw InvalidManagedSkillRecordSchemaError(recordPath.toString(), "source_path must be the managed source directory")
    }
  }

  private fun safeName(name: String): String = try {
    skillbill.managedskill.model.requireSafeManagedSkillName(name)
  } catch (error: IllegalArgumentException) {
    throw InvalidManagedSkillRecordSchemaError(name, "unsafe managed skill name", error)
  }

  private fun safeHash(hash: String): String {
    if (!hash.matches(Regex("^[a-f0-9]{64}$"))) throw InvalidManagedSkillRecordSchemaError(hash, "unsafe content hash")
    return hash
  }

  private fun confined(vararg segments: String): Path = requireConfined(segments.fold(stateRoot) { path, segment -> path.resolve(segment) })

  private fun requireConfined(path: Path): Path {
    val normalized = path.toAbsolutePath().normalize()
    if (!normalized.startsWith(stateRoot)) throw InvalidManagedSkillRecordSchemaError(path.toString(), "path escapes managed state root")
    return normalized
  }

  private fun requireRealAncestors(directory: Path) {
    var current = stateRoot.root
    stateRoot.forEach { segment ->
      current = current.resolve(segment)
      if (Files.exists(current, NOFOLLOW_LINKS) && (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW_LINKS))) {
        throw InvalidManagedSkillRecordSchemaError(current.toString(), "managed state parent is not a real directory")
      }
    }
    if (!directory.startsWith(stateRoot)) throw InvalidManagedSkillRecordSchemaError(directory.toString(), "path escapes managed state root")
    stateRoot.relativize(directory).forEach { segment ->
      current = current.resolve(segment)
      if (!Files.exists(current, NOFOLLOW_LINKS) || Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW_LINKS)) {
        throw InvalidManagedSkillRecordSchemaError(current.toString(), "managed state parent is not a real directory")
      }
    }
  }

  private fun readNoFollow(directory: RecordDirectory, name: Path, displayPath: Path): ByteArray = try {
    val attributes = directory.attributes(name)
    if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() > MAX_RECORD_BYTES) {
      throw InvalidManagedSkillRecordSchemaError(displayPath.toString(), "managed record must be a bounded regular file")
    }
    directory.newByteChannel(name, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
      val size = channel.size()
      if (size < 0 || size > MAX_RECORD_BYTES) throw IllegalArgumentException("managed record is too large")
      val bytes = readBounded(channel, size.toInt(), displayPath)
      val after = directory.attributes(name)
      if (!after.isRegularFile || after.isSymbolicLink || attributes.size() != after.size() ||
        attributes.lastModifiedTime() != after.lastModifiedTime() ||
        attributes.fileKey() != null && after.fileKey() != null && attributes.fileKey() != after.fileKey()
      ) throw InvalidManagedSkillRecordSchemaError(displayPath.toString(), "managed record changed while being read")
      bytes
    }
  } catch (error: InvalidManagedSkillRecordSchemaError) {
    throw error
  } catch (error: Exception) {
    throw InvalidManagedSkillRecordSchemaError(displayPath.toString(), "record cannot be read without following links", error)
  }

  private fun <T> withRecordDirectory(
    name: String,
    block: (RecordDirectory, Path) -> T,
  ): T {
    val directory = recordPath(name).parent
    requireRealAncestors(directory)
    requireStableStateRoot()
    return Files.newDirectoryStream(stateRoot).use { rootStream ->
      val secureRoot = rootStream as? SecureDirectoryStream<Path>
      if (secureRoot == null) {
        throw InvalidManagedSkillRecordSchemaError(
          directory.toString(),
          "managed records require identity-bound directory access on this filesystem",
        )
      }
      val openedRoot = secureRoot.getFileAttributeView(
        Path.of("."),
        java.nio.file.attribute.BasicFileAttributeView::class.java,
        NOFOLLOW_LINKS,
      ).readAttributes()
      if (!openedRoot.isDirectory || openedRoot.isSymbolicLink || stateRootIdentity == null ||
        openedRoot.fileKey() == null || openedRoot.fileKey() != stateRootIdentity
      ) {
        throw InvalidManagedSkillRecordSchemaError(stateRoot.toString(), "managed state root identity changed before secure access")
      }
      secureRoot.newDirectoryStream(Path.of("managed-skills"), NOFOLLOW_LINKS).use { managed ->
        managed.newDirectoryStream(Path.of(name), NOFOLLOW_LINKS).use { recordDirectory ->
          block(SecureRecordDirectory(recordDirectory), directory)
        }
      }.also { requireStableStateRoot() }
    }
  }

  private fun exists(directory: RecordDirectory, name: Path): Boolean = directory.exists(name)

  private interface RecordDirectory {
    fun attributes(name: Path): java.nio.file.attribute.BasicFileAttributes
    fun newByteChannel(name: Path, options: Set<java.nio.file.OpenOption>): SeekableByteChannel
    fun atomicMove(source: Path, target: Path)
    fun deleteFile(name: Path)
    fun exists(name: Path): Boolean = try {
      attributes(name)
      true
    } catch (_: java.nio.file.NoSuchFileException) {
      false
    }
  }

  private class SecureRecordDirectory(
    private val directory: SecureDirectoryStream<Path>,
  ) : RecordDirectory {
    override fun attributes(name: Path) = directory.getFileAttributeView(
      name,
      java.nio.file.attribute.BasicFileAttributeView::class.java,
      NOFOLLOW_LINKS,
    ).readAttributes()

    override fun newByteChannel(name: Path, options: Set<java.nio.file.OpenOption>) =
      directory.newByteChannel(name, options)

    override fun atomicMove(source: Path, target: Path) = directory.move(source, directory, target)
    override fun deleteFile(name: Path) = directory.deleteFile(name)
  }

  private fun requireStableStateRoot() {
    val attributes = Files.readAttributes(
      stateRoot,
      java.nio.file.attribute.BasicFileAttributes::class.java,
      NOFOLLOW_LINKS,
    )
    if (!attributes.isDirectory || attributes.isSymbolicLink ||
      stateRootIdentity != null && attributes.fileKey() != null && attributes.fileKey() != stateRootIdentity
    ) {
      throw InvalidManagedSkillRecordSchemaError(stateRoot.toString(), "managed state root identity changed")
    }
  }

  private fun readBounded(channel: SeekableByteChannel, expectedSize: Int, path: Path): ByteArray {
    val buffer = java.nio.ByteBuffer.allocate(expectedSize)
    while (buffer.hasRemaining()) {
      if (channel.read(buffer) < 0) break
    }
    if (buffer.hasRemaining() || channel.read(java.nio.ByteBuffer.allocate(1)) >= 0) {
      throw InvalidManagedSkillRecordSchemaError(path.toString(), "managed record changed size while being read")
    }
    return buffer.array()
  }

  private fun createConfinedDirectories(directory: Path) {
    var current = stateRoot.root
    stateRoot.forEach { segment ->
      current = current.resolve(segment)
      if (Files.exists(current, NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
        throw InvalidManagedSkillRecordSchemaError(current.toString(), "managed state parent is a symbolic link")
      }
    }
    stateRoot.relativize(directory).forEach { segment ->
      current = current.resolve(segment)
      if (Files.exists(current, NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW_LINKS)) {
          throw InvalidManagedSkillRecordSchemaError(current.toString(), "managed state parent is not a real directory")
        }
      } else {
        Files.createDirectory(current)
      }
    }
  }

  private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private fun ManagedSkillRecord.toWire(): Map<String, Any?> = linkedMapOf(
  "contract_version" to contractVersion,
  "name" to name,
  "source_kind" to sourceKind.name.lowercase(),
  "source_path" to sourcePath.toAbsolutePath().normalize().toString(),
  "active_content_hash" to activeContentHash,
  "selected_targets" to selectedTargets.sortedBy { it.stableIdentity }.map {
    linkedMapOf("provider" to it.provider, "skills_path" to it.skillsPath.toAbsolutePath().normalize().toString())
  },
  "imported_at" to importedAt.toString(),
  "updated_at" to updatedAt.toString(),
)

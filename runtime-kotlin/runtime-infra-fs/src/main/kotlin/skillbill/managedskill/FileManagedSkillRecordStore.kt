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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.SecureDirectoryStream
import java.security.MessageDigest
import java.time.Instant

class FileManagedSkillRecordStore(stateRoot: Path) {
  companion object {
    const val EXPECTED_ABSENT = "absent"
    private const val MAX_RECORD_BYTES = 1024 * 1024
  }
  private val stateRoot = stateRoot.toAbsolutePath().normalize()
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
    val raw = try {
      mapper.readValue(readNoFollow(confinedPath), object : TypeReference<Map<String, Any?>>() {})
    } catch (error: InvalidManagedSkillRecordSchemaError) {
      throw error
    } catch (error: Exception) {
      throw InvalidManagedSkillRecordSchemaError(confinedPath.toString(), "record is unreadable JSON", error)
    }
    ManagedSkillRecordSchemaValidator.validate(raw, confinedPath.toString())
    val record = mapRecord(raw, confinedPath)
    validateRecordPaths(record, confinedPath)
    if (expectedName != null && record.name != expectedName) {
      throw InvalidManagedSkillRecordSchemaError(confinedPath.toString(), "record name does not match its managed directory")
    }
    return record
  }

  fun write(record: ManagedSkillRecord, expectedDigest: String? = null) {
    val name = safeName(record.name)
    val path = recordPath(name)
    val raw = record.toWire()
    ManagedSkillRecordSchemaValidator.validate(raw, path.toString())
    validateRecordPaths(record, path)
    createConfinedDirectories(path.parent)
    secureDirectory(path.parent).use { directory ->
      val lockChannel = directory.newByteChannel(Path.of(".record.lock"), setOf(CREATE, WRITE, NOFOLLOW_LINKS)) as? FileChannel
        ?: throw IllegalStateException("Secure managed-record locking is unavailable.")
      lockChannel.use {
        it.lock().use {
          val currentDigest = readSecureIfPresent(directory, Path.of("record.json"), path)?.let(::digest)
          if (expectedDigest != null && expectedDigest != currentDigest && !(expectedDigest == EXPECTED_ABSENT && currentDigest == null)) {
            throw IllegalStateException("Managed skill record changed after preview.")
          }
          publish(directory, Path.of("record.json"), mapper.writeValueAsBytes(raw))
        }
      }
    }
  }

  fun digest(name: String): String? {
    val path = recordPath(name)
    requireRealAncestors(path.parent)
    rejectLinkOrNonRegular(path)
    return if (Files.exists(path, NOFOLLOW_LINKS)) digest(readNoFollow(path)) else null
  }

  private fun publish(directory: SecureDirectoryStream<Path>, target: Path, bytes: ByteArray) {
    val temporary = Path.of(".record-${java.util.UUID.randomUUID()}.json")
    try {
      val channel = directory.newByteChannel(temporary, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)) as? FileChannel
        ?: throw IllegalStateException("Secure atomic managed-record publication is unavailable.")
      channel.use {
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) it.write(buffer)
        it.force(true)
      }
      try {
        directory.move(temporary, directory, target)
      } catch (error: java.nio.file.AtomicMoveNotSupportedException) {
        throw IllegalStateException("Atomic managed-record publication is unavailable.", error)
      }
    } finally {
      try { directory.deleteFile(temporary) } catch (_: java.nio.file.NoSuchFileException) { }
    }
  }

  private fun secureDirectory(path: Path): SecureDirectoryStream<Path> {
    val stream = Files.newDirectoryStream(path)
    return stream as? SecureDirectoryStream<Path> ?: run {
      stream.close()
      throw IllegalStateException("Secure managed-record operations are unavailable on this filesystem.")
    }
  }

  private fun readSecureIfPresent(
    directory: SecureDirectoryStream<Path>,
    name: Path,
    displayPath: Path,
  ): ByteArray? = try {
    val view = directory.getFileAttributeView(
      name, java.nio.file.attribute.BasicFileAttributeView::class.java, NOFOLLOW_LINKS,
    ) ?: return null
    val attributes = view.readAttributes()
    if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() > MAX_RECORD_BYTES) {
      throw InvalidManagedSkillRecordSchemaError(displayPath.toString(), "managed record must be a bounded regular file")
    }
    directory.newByteChannel(name, setOf(READ, NOFOLLOW_LINKS)).use {
      readBounded(it, attributes.size().toInt(), displayPath)
    }
  } catch (_: java.nio.file.NoSuchFileException) {
    null
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

  private fun rejectLinkOrNonRegular(path: Path) {
    if (Files.exists(path, NOFOLLOW_LINKS) && (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS))) {
      throw InvalidManagedSkillRecordSchemaError(path.toString(), "managed record is not a regular file")
    }
  }

  private fun readNoFollow(path: Path): ByteArray = try {
    val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() > MAX_RECORD_BYTES) {
      throw InvalidManagedSkillRecordSchemaError(path.toString(), "managed record must be a bounded regular file")
    }
    FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
      val size = channel.size()
      if (size < 0 || size > MAX_RECORD_BYTES) throw IllegalArgumentException("managed record is too large")
      readBounded(channel, size.toInt(), path)
    }
  } catch (error: InvalidManagedSkillRecordSchemaError) {
    throw error
  } catch (error: Exception) {
    throw InvalidManagedSkillRecordSchemaError(path.toString(), "record cannot be read without following links", error)
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

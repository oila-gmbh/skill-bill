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
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.time.Instant

class FileManagedSkillRecordStore(stateRoot: Path) {
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

  fun readPath(path: Path): ManagedSkillRecord = readPath(path, null)

  private fun readPath(path: Path, expectedName: String?): ManagedSkillRecord {
    val confinedPath = requireConfined(path)
    val raw = try {
      mapper.readValue(Files.readString(confinedPath), object : TypeReference<Map<String, Any?>>() {})
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
    val path = recordPath(record.name)
    val raw = record.toWire()
    ManagedSkillRecordSchemaValidator.validate(raw, path.toString())
    validateRecordPaths(record, path)
    createConfinedDirectories(path.parent)
    val lockPath = path.parent.resolve(".record.lock")
    FileChannel.open(lockPath, CREATE, WRITE).use { lockChannel ->
      lockChannel.lock().use {
        val currentDigest = if (Files.exists(path, NOFOLLOW_LINKS)) digest(Files.readAllBytes(path)) else null
        if (expectedDigest != null && currentDigest != expectedDigest) {
          throw IllegalStateException("Managed skill record changed after preview.")
        }
        publish(path, mapper.writeValueAsBytes(raw))
      }
    }
  }

  fun digest(name: String): String? {
    val path = recordPath(name)
    return if (Files.exists(path, NOFOLLOW_LINKS)) digest(Files.readAllBytes(path)) else null
  }

  private fun publish(path: Path, bytes: ByteArray) {
    val temporary = Files.createTempFile(path.parent, ".record-", ".json")
    try {
      FileChannel.open(temporary, WRITE).use { channel ->
        channel.write(java.nio.ByteBuffer.wrap(bytes))
        channel.force(true)
      }
      try {
        Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
      } catch (error: java.nio.file.AtomicMoveNotSupportedException) {
        throw IllegalStateException("Atomic managed-record publication is unavailable.", error)
      }
      FileChannel.open(path.parent, READ).use { it.force(true) }
    } finally {
      Files.deleteIfExists(temporary)
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

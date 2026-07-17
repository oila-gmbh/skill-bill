package skillbill.managedskill

import skillbill.contracts.managedskill.ManagedSkillRecordSchemaValidator
import skillbill.managedskill.model.AgentSkillTargetId
import skillbill.managedskill.model.ManagedSkillRecord
import skillbill.managedskill.model.ManagedSkillSourceKind
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val processLocks = ConcurrentHashMap<String, ReentrantLock>()

internal fun ManagedSkillRecordStoreContext.write(record: ManagedSkillRecord, expectedDigest: String?) {
  val name = safeName(record.name)
  val path = recordPath(name)
  val raw = record.toWire()
  ManagedSkillRecordSchemaValidator.validate(raw, path.toString())
  validateRecordPaths(record, path)
  createConfinedDirectories(path.parent)
  requireRealAncestors(path.parent)
  withRecordDirectory(name) { directory, displayDirectory ->
    val lock = processLocks.computeIfAbsent(displayDirectory.toUri().toString()) { ReentrantLock() }
    lock.withLock {
      directory.newByteChannel(Path.of(".record.lock"), setOf(CREATE, WRITE, NOFOLLOW_LINKS)).use { channel ->
        val fileLock = (channel as? FileChannel)?.lock()
        try {
          check(expectedDigestMatches(expectedDigest, currentDigest(directory, path, name))) {
            "Managed skill record changed after preview."
          }
          publish(directory, displayDirectory, mapper.writeValueAsBytes(raw))
        } finally {
          fileLock?.release()
        }
      }
    }
  }
}

private fun expectedDigestMatches(expected: String?, current: String?): Boolean {
  if (expected == null || expected == current) return true
  return expected == FileManagedSkillRecordStore.EXPECTED_ABSENT && current == null
}

private fun ManagedSkillRecordStoreContext.publish(
  directory: RecordDirectory,
  displayDirectory: Path,
  bytes: ByteArray,
) {
  val temporaryName = displayDirectory.fileSystem.getPath(".record-${UUID.randomUUID()}.json")
  val recordName = displayDirectory.fileSystem.getPath("record.json")
  val temporary = displayDirectory.resolve(temporaryName)
  try {
    directory.newByteChannel(temporaryName, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)).use { channel ->
      val buffer = ByteBuffer.wrap(bytes)
      while (buffer.hasRemaining()) channel.write(buffer)
      (channel as? FileChannel)?.force(true)
    }
    atomicPublication {
      atomicReplace?.invoke(temporary, displayDirectory.resolve("record.json"))
        ?: directory.atomicMove(temporaryName, recordName)
      forceDirectory(displayDirectory)
    }
  } finally {
    deleteTemporary(directory, temporaryName)
  }
}

private inline fun atomicPublication(operation: () -> Unit) {
  runCatching(operation).getOrElse { error ->
    if (error is Exception) {
      throw IllegalStateException("Atomic managed-record publication is unavailable.", error)
    }
    throw error
  }
}

private fun deleteTemporary(directory: RecordDirectory, temporaryName: Path) {
  runCatching { directory.deleteFile(temporaryName) }.getOrElse { error ->
    if (error !is NoSuchFileException) throw error
  }
}

internal fun ManagedSkillRecordStoreContext.mapRecord(raw: Map<String, Any?>, path: Path): ManagedSkillRecord =
  managedRecordOperation(path, "record values cannot be mapped") {
    @Suppress("UNCHECKED_CAST")
    val rawTargets = raw.getValue("selected_targets") as List<Map<String, String>>
    val targets = rawTargets.mapTo(linkedSetOf()) { target -> mapTarget(target) }
    require(targets.size == rawTargets.size) { "selected targets contain duplicate canonical identities" }
    ManagedSkillRecord(
      name = raw.getValue("name") as String,
      sourceKind = ManagedSkillSourceKind.valueOf((raw.getValue("source_kind") as String).uppercase()),
      sourcePath = normalizedAbsolutePath(raw.getValue("source_path") as String, "source path"),
      activeContentHash = raw.getValue("active_content_hash") as String,
      selectedTargets = targets,
      importedAt = Instant.parse(raw.getValue("imported_at") as String),
      updatedAt = Instant.parse(raw.getValue("updated_at") as String),
      contractVersion = raw.getValue("contract_version") as String,
    )
  }

private fun ManagedSkillRecordStoreContext.mapTarget(raw: Map<String, String>): AgentSkillTargetId {
  val provider = raw.getValue("provider")
  require(provider.matches(Regex("^[a-z0-9][a-z0-9-]{0,62}$"))) {
    "provider must be a stable safe identifier"
  }
  return AgentSkillTargetId(provider, normalizedAbsolutePath(raw.getValue("skills_path"), "target path"))
}

private fun ManagedSkillRecordStoreContext.normalizedAbsolutePath(value: String, label: String): Path =
  stateRoot.fileSystem.getPath(value).also { path ->
    require(path.isAbsolute && path == path.normalize()) { "$label must be absolute and normalized" }
  }

private fun ManagedSkillRecord.toWire(): Map<String, Any?> = linkedMapOf(
  "contract_version" to contractVersion,
  "name" to name,
  "source_kind" to sourceKind.name.lowercase(),
  "source_path" to sourcePath.toAbsolutePath().normalize().toString(),
  "active_content_hash" to activeContentHash,
  "selected_targets" to selectedTargets.sortedBy { it.stableIdentity }.map { target ->
    linkedMapOf(
      "provider" to target.provider,
      "skills_path" to target.skillsPath.toAbsolutePath().normalize().toString(),
    )
  },
  "imported_at" to importedAt.toString(),
  "updated_at" to updatedAt.toString(),
)

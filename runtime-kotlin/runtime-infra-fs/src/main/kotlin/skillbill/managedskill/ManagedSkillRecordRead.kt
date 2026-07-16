package skillbill.managedskill

import com.fasterxml.jackson.core.type.TypeReference
import skillbill.contracts.managedskill.ManagedSkillRecordSchemaValidator
import skillbill.managedskill.model.ManagedSkillRecord
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

private const val MAX_RECORD_BYTES = 1024 * 1024

internal fun ManagedSkillRecordStoreContext.read(name: String): ManagedSkillRecord {
  val safeName = safeName(name)
  return readPath(recordPath(safeName), safeName)
}

internal fun ManagedSkillRecordStoreContext.readPath(path: Path): ManagedSkillRecord {
  val confinedPath = requireConfined(path)
  val managedRoot = stateRoot.resolve("managed-skills")
  val relative = managedRecordOperation(path, "record path is outside managed-skills") {
    managedRoot.relativize(confinedPath)
  }
  requireManagedRecordLayout(path, confinedPath, managedRoot, relative)
  return readPath(confinedPath, safeName(relative.getName(0).toString()))
}

private fun ManagedSkillRecordStoreContext.readPath(path: Path, expectedName: String): ManagedSkillRecord {
  val confinedPath = requireConfined(path)
  requireRealAncestors(confinedPath.parent)
  return withRecordDirectory(expectedName) { directory, displayDirectory ->
    readValidated(directory, displayDirectory.resolve("record.json"), expectedName).first
  }
}

internal fun ManagedSkillRecordStoreContext.readValidated(
  directory: RecordDirectory,
  path: Path,
  expectedName: String,
): Pair<ManagedSkillRecord, ByteArray> {
  val confinedPath = requireConfined(path)
  val bytes = readNoFollow(directory, Path.of("record.json"), confinedPath)
  val raw = managedRecordOperation(confinedPath, "record is unreadable JSON") {
    mapper.readValue(bytes, object : TypeReference<Map<String, Any?>>() {})
  }
  ManagedSkillRecordSchemaValidator.validate(raw, confinedPath.toString())
  val record = mapRecord(raw, confinedPath)
  validateRecordPaths(record, confinedPath)
  if (record.name != expectedName) {
    invalidManagedRecord(confinedPath, "record name does not match its managed directory")
  }
  return record to bytes
}

internal fun ManagedSkillRecordStoreContext.digest(name: String): String? {
  val path = recordPath(name)
  requireRealAncestors(path.parent)
  val safeName = safeName(name)
  return withRecordDirectory(safeName) { directory, _ -> currentDigest(directory, path, safeName) }
}

internal fun ManagedSkillRecordStoreContext.currentDigest(
  directory: RecordDirectory,
  path: Path,
  name: String,
): String? = if (directory.exists(Path.of("record.json"))) {
  digestBytes(readValidated(directory, path, name).second)
} else {
  null
}

private fun readNoFollow(directory: RecordDirectory, name: Path, displayPath: Path): ByteArray =
  managedRecordOperation(displayPath, "record cannot be read without following links") {
    val before = directory.attributes(name)
    requireBoundedRegularRecord(before, displayPath)
    directory.newByteChannel(name, setOf(READ, java.nio.file.LinkOption.NOFOLLOW_LINKS)).use { channel ->
      val size = channel.size()
      require(size in 0..MAX_RECORD_BYTES.toLong()) { "managed record is too large" }
      val bytes = readBounded(channel, size.toInt(), displayPath)
      requireStableRecord(before, directory.attributes(name), displayPath)
      bytes
    }
  }

private fun requireBoundedRegularRecord(attributes: BasicFileAttributes, path: Path) {
  if (!attributes.isRegularFile || attributes.isSymbolicLink) {
    invalidManagedRecord(path, "managed record must be a bounded regular file")
  }
  if (attributes.size() > MAX_RECORD_BYTES) {
    invalidManagedRecord(path, "managed record must be a bounded regular file")
  }
}

private fun requireStableRecord(before: BasicFileAttributes, after: BasicFileAttributes, path: Path) {
  if (!after.isRegularFile || after.isSymbolicLink) {
    invalidManagedRecord(path, "managed record changed while being read")
  }
  if (!hasSameFileIdentity(before, after)) {
    invalidManagedRecord(path, "managed record changed while being read")
  }
  if (before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime()) {
    invalidManagedRecord(path, "managed record changed while being read")
  }
}

private fun readBounded(channel: SeekableByteChannel, expectedSize: Int, path: Path): ByteArray {
  val buffer = ByteBuffer.allocate(expectedSize)
  while (buffer.hasRemaining()) {
    if (channel.read(buffer) < 0) break
  }
  if (buffer.hasRemaining() || channel.read(ByteBuffer.allocate(1)) >= 0) {
    invalidManagedRecord(path, "managed record changed size while being read")
  }
  return buffer.array()
}

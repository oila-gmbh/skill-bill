package skillbill.managedskill

import skillbill.managedskill.model.ManagedSkillRecord
import java.nio.file.Path

internal fun ManagedSkillRecordStoreContext.recordPath(name: String): Path =
  confined("managed-skills", safeName(name), "record.json")

internal fun ManagedSkillRecordStoreContext.sourceRoot(name: String): Path =
  confined("managed-skills", safeName(name), "source")

internal fun ManagedSkillRecordStoreContext.snapshotRoot(name: String, contentHash: String): Path =
  confined("installed-skills", "${safeName(name)}-${safeHash(contentHash)}")

internal fun ManagedSkillRecordStoreContext.validateRecordPaths(record: ManagedSkillRecord, recordPath: Path) {
  if (!record.sourcePath.isAbsolute || record.sourcePath != record.sourcePath.normalize()) {
    invalidManagedRecord(recordPath, "source_path must be absolute and normalized")
  }
  if (record.sourcePath != sourceRoot(record.name)) {
    invalidManagedRecord(recordPath, "source_path must be the managed source directory")
  }
}

internal fun safeName(name: String): String = managedRecordOperation(name, "unsafe managed skill name") {
  skillbill.managedskill.model.requireSafeManagedSkillName(name)
}

private fun safeHash(hash: String): String {
  if (!hash.matches(Regex("^[a-f0-9]{64}$"))) invalidManagedRecord(hash, "unsafe content hash")
  return hash
}

private fun ManagedSkillRecordStoreContext.confined(vararg segments: String): Path =
  requireConfined(segments.fold(stateRoot) { path, segment -> path.resolve(segment) })

internal fun ManagedSkillRecordStoreContext.requireConfined(path: Path): Path {
  val normalized = path.toAbsolutePath().normalize()
  if (!normalized.startsWith(stateRoot)) invalidManagedRecord(path, "path escapes managed state root")
  return normalized
}

internal fun requireManagedRecordLayout(requestedPath: Path, confinedPath: Path, managedRoot: Path, relative: Path) {
  if (!confinedPath.startsWith(managedRoot)) {
    invalidManagedRecord(requestedPath, "record path is outside managed-skills")
  }
  if (relative.nameCount != 2 || relative.fileName.toString() != "record.json") {
    invalidManagedRecord(requestedPath, "record path must be managed-skills/<name>/record.json")
  }
}

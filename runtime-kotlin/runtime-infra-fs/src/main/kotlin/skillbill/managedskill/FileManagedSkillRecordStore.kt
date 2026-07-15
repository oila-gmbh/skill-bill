package skillbill.managedskill

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
import java.time.Instant

class FileManagedSkillRecordStore(private val stateRoot: Path) {
  private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

  fun recordPath(name: String): Path = stateRoot.resolve("managed-skills").resolve(name).resolve("record.json")
  fun sourceRoot(name: String): Path = stateRoot.resolve("managed-skills").resolve(name).resolve("source")
  fun snapshotRoot(name: String, contentHash: String): Path =
    stateRoot.resolve("installed-skills").resolve("$name-$contentHash")

  fun read(name: String): ManagedSkillRecord = readPath(recordPath(name))

  fun readPath(path: Path): ManagedSkillRecord {
    val raw = try {
      mapper.readValue(Files.readString(path), object : TypeReference<Map<String, Any?>>() {})
    } catch (error: InvalidManagedSkillRecordSchemaError) {
      throw error
    } catch (error: Exception) {
      throw InvalidManagedSkillRecordSchemaError(path.toString(), "record is unreadable JSON", error)
    }
    ManagedSkillRecordSchemaValidator.validate(raw, path.toString())
    return mapRecord(raw, path)
  }

  fun write(record: ManagedSkillRecord) {
    val path = recordPath(record.name)
    Files.createDirectories(path.parent)
    val raw = record.toWire()
    ManagedSkillRecordSchemaValidator.validate(raw, path.toString())
    val temporary = Files.createTempFile(path.parent, ".record-", ".json")
    try {
      Files.writeString(temporary, mapper.writeValueAsString(raw))
      try {
        Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
      } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(temporary, path, REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private fun mapRecord(raw: Map<String, Any?>, path: Path): ManagedSkillRecord = try {
    @Suppress("UNCHECKED_CAST")
    val targets = (raw.getValue("selected_targets") as List<Map<String, String>>).mapTo(linkedSetOf()) {
      AgentSkillTargetId(it.getValue("provider"), Path.of(it.getValue("skills_path")).toAbsolutePath().normalize())
    }
    ManagedSkillRecord(
      name = raw.getValue("name") as String,
      sourceKind = ManagedSkillSourceKind.valueOf((raw.getValue("source_kind") as String).uppercase()),
      sourcePath = Path.of(raw.getValue("source_path") as String),
      activeContentHash = raw.getValue("active_content_hash") as String,
      selectedTargets = targets,
      importedAt = Instant.parse(raw.getValue("imported_at") as String),
      updatedAt = Instant.parse(raw.getValue("updated_at") as String),
      contractVersion = raw.getValue("contract_version") as String,
    )
  } catch (error: Exception) {
    throw InvalidManagedSkillRecordSchemaError(path.toString(), "record values cannot be mapped", error)
  }
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

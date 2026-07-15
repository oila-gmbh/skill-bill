package skillbill.managedskill

import skillbill.error.InvalidManagedSkillRecordSchemaError
import skillbill.managedskill.model.AgentSkillTargetId
import skillbill.managedskill.model.ManagedSkillRecord
import skillbill.managedskill.model.ManagedSkillSourceKind
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class FileManagedSkillRecordStoreTest {
  @Test
  fun `round trips a validated record with multiple provider paths`() {
    val root = Files.createTempDirectory("managed-records")
    val store = FileManagedSkillRecordStore(root)
    val now = Instant.parse("2026-07-15T12:00:00Z")
    val record = ManagedSkillRecord(
      name = "sample-skill",
      sourceKind = ManagedSkillSourceKind.DIRECTORY,
      sourcePath = store.sourceRoot("sample-skill"),
      activeContentHash = "a".repeat(64),
      selectedTargets = setOf(
        AgentSkillTargetId("claude", root.resolve("claude-one").toAbsolutePath()),
        AgentSkillTargetId("claude", root.resolve("claude-two").toAbsolutePath()),
      ),
      importedAt = now,
      updatedAt = now,
    )
    store.write(record)
    assertEquals(record, store.read(record.name))
  }

  @Test
  fun `every read rejects an invalid record loudly`() {
    val root = Files.createTempDirectory("managed-records")
    val store = FileManagedSkillRecordStore(root)
    val path = store.recordPath("bad")
    Files.createDirectories(path.parent)
    path.writeText("{\"contract_version\":\"0.1\",\"name\":\"bad\"}")
    assertFailsWith<InvalidManagedSkillRecordSchemaError> { store.read("bad") }
  }

  @Test
  fun `rejects unsafe names before creating directories`() {
    val root = Files.createTempDirectory("managed-records")
    val store = FileManagedSkillRecordStore(root)
    assertFailsWith<InvalidManagedSkillRecordSchemaError> { store.recordPath("../escape") }
    assertEquals(false, Files.exists(root.resolve("managed-skills")))
  }

  @Test
  fun `rejects duplicate keys and a record name that differs from its path`() {
    val root = Files.createTempDirectory("managed-records")
    val store = FileManagedSkillRecordStore(root)
    val path = store.recordPath("expected")
    Files.createDirectories(path.parent)
    path.writeText("""{"contract_version":"0.1","name":"expected","name":"other"}""")
    assertFailsWith<InvalidManagedSkillRecordSchemaError> { store.read("expected") }
  }

  @Test
  fun `public path reads require the canonical managed record layout`() {
    val root = Files.createTempDirectory("managed-records")
    val store = FileManagedSkillRecordStore(root)
    val outsideShape = root.resolve("record.json")
    outsideShape.writeText("{}")
    assertFailsWith<InvalidManagedSkillRecordSchemaError> { store.readPath(outsideShape) }

    val misplaced = store.recordPath("expected")
    Files.createDirectories(misplaced.parent)
    misplaced.writeText(validWire(root, "other"))
    assertFailsWith<InvalidManagedSkillRecordSchemaError> { store.readPath(misplaced) }
  }

  @Test
  fun `requires compare and swap when an expected digest is supplied`() {
    val root = Files.createTempDirectory("managed-records")
    val store = FileManagedSkillRecordStore(root)
    val now = Instant.parse("2026-07-15T12:00:00Z")
    val record = ManagedSkillRecord(
      name = "sample-skill",
      sourceKind = ManagedSkillSourceKind.DIRECTORY,
      sourcePath = store.sourceRoot("sample-skill"),
      activeContentHash = "a".repeat(64),
      selectedTargets = setOf(AgentSkillTargetId("claude", root.resolve("claude").toAbsolutePath())),
      importedAt = now,
      updatedAt = now,
    )
    store.write(record)
    assertNotNull(store.digest(record.name))
    assertFailsWith<IllegalStateException> { store.write(record, "0".repeat(64)) }
  }

  @Test
  fun `matching digest atomically replaces the current record`() {
    val root = Files.createTempDirectory("managed-records")
    val store = FileManagedSkillRecordStore(root)
    val now = Instant.parse("2026-07-15T12:00:00Z")
    val record = ManagedSkillRecord(
      name = "sample-skill", sourceKind = ManagedSkillSourceKind.DIRECTORY,
      sourcePath = store.sourceRoot("sample-skill"), activeContentHash = "a".repeat(64),
      selectedTargets = setOf(AgentSkillTargetId("claude", root.resolve("claude").toAbsolutePath())),
      importedAt = now, updatedAt = now,
    )
    store.write(record)
    val digest = assertNotNull(store.digest(record.name))
    val replacement = record.copy(activeContentHash = "b".repeat(64), updatedAt = now.plusSeconds(1))
    store.write(replacement, digest)
    assertEquals(replacement, store.read(record.name))
  }

  @Test
  fun `digest and compare and swap reject duplicate key records`() {
    val root = Files.createTempDirectory("managed-records")
    val store = FileManagedSkillRecordStore(root)
    val path = store.recordPath("sample-skill")
    Files.createDirectories(path.parent)
    path.writeText(validWire(root, "sample-skill").replace(
      "\"name\":\"sample-skill\"",
      "\"name\":\"sample-skill\",\"name\":\"sample-skill\"",
    ))
    assertFailsWith<InvalidManagedSkillRecordSchemaError> { store.digest("sample-skill") }

    val now = Instant.parse("2026-07-15T12:00:00Z")
    val replacement = ManagedSkillRecord(
      name = "sample-skill", sourceKind = ManagedSkillSourceKind.DIRECTORY,
      sourcePath = store.sourceRoot("sample-skill"), activeContentHash = "b".repeat(64),
      selectedTargets = setOf(AgentSkillTargetId("claude", root.resolve("claude").toAbsolutePath())),
      importedAt = now, updatedAt = now,
    )
    assertFailsWith<InvalidManagedSkillRecordSchemaError> { store.write(replacement, "0".repeat(64)) }
  }

  @Test
  fun `compare and swap can require an absent record`() {
    val root = Files.createTempDirectory("managed-records")
    val store = FileManagedSkillRecordStore(root)
    val now = Instant.parse("2026-07-15T12:00:00Z")
    val record = ManagedSkillRecord(
      name = "sample-skill", sourceKind = ManagedSkillSourceKind.DIRECTORY,
      sourcePath = store.sourceRoot("sample-skill"), activeContentHash = "a".repeat(64),
      selectedTargets = setOf(AgentSkillTargetId("claude", root.resolve("claude").toAbsolutePath())),
      importedAt = now, updatedAt = now,
    )
    store.write(record, FileManagedSkillRecordStore.EXPECTED_ABSENT)
    assertFailsWith<IllegalStateException> { store.write(record, FileManagedSkillRecordStore.EXPECTED_ABSENT) }
  }

  private fun validWire(root: java.nio.file.Path, name: String): String = """{
    "contract_version":"0.1","name":"$name","source_kind":"directory",
    "source_path":"${root.resolve("managed-skills/$name/source")}",
    "active_content_hash":"${"a".repeat(64)}",
    "selected_targets":[{"provider":"codex","skills_path":"${root.resolve("codex")}"}],
    "imported_at":"2026-07-15T12:00:00Z","updated_at":"2026-07-15T12:00:00Z"
  }"""
}

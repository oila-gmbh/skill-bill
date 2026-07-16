package skillbill.managedskill

import skillbill.error.InvalidManagedSkillRecordSchemaError
import skillbill.managedskill.model.AgentSkillTargetId
import skillbill.managedskill.model.ManagedSkillRecord
import skillbill.managedskill.model.ManagedSkillSourceKind
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class FileManagedSkillRecordStoreTest {
  private fun store(root: java.nio.file.Path) = FileManagedSkillRecordStore.fromStateRoot(root)

  @Test
  fun `production construction derives managed roots from home`() {
    val home = Files.createTempDirectory("managed-records-home")
    val store = FileManagedSkillRecordStore(home)

    assertEquals(home.resolve(".skill-bill/managed-skills/sample-skill/record.json"), store.recordPath("sample-skill"))
    assertEquals(home.resolve(".skill-bill/managed-skills/sample-skill/source"), store.sourceRoot("sample-skill"))
    assertEquals(
      home.resolve(".skill-bill/installed-skills/sample-skill-${"a".repeat(64)}"),
      store.snapshotRoot("sample-skill", "a".repeat(64)),
    )
  }

  @Test
  fun `round trips records without secure directory streams`() {
    val root = Files.createTempDirectory("managed-records-fallback")
    val store = FileManagedSkillRecordStore(root, useSecureDirectoryStreams = false)
    val now = Instant.parse("2026-07-15T12:00:00Z")
    val record = ManagedSkillRecord(
      name = "sample-skill",
      sourceKind = ManagedSkillSourceKind.DIRECTORY,
      sourcePath = store.sourceRoot("sample-skill"),
      activeContentHash = "a".repeat(64),
      selectedTargets = setOf(AgentSkillTargetId("codex", root.resolve("codex"))),
      importedAt = now,
      updatedAt = now,
    )
    store.write(record)
    assertEquals(record, store.read("sample-skill"))
    val digest = assertNotNull(store.digest("sample-skill"))
    val replacement = record.copy(activeContentHash = "b".repeat(64), updatedAt = now.plusSeconds(1))
    store.write(replacement, digest)
    assertEquals(replacement, store.read("sample-skill"))
  }

  @Test
  fun `round trips a validated record with multiple provider paths`() {
    val root = Files.createTempDirectory("managed-records")
    val store = store(root)
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
    val store = store(root)
    val path = store.recordPath("bad")
    Files.createDirectories(path.parent)
    path.writeText("{\"contract_version\":\"0.1\",\"name\":\"bad\"}")
    assertFailsWith<InvalidManagedSkillRecordSchemaError> { store.read("bad") }
  }

  @Test
  fun `rejects unsafe names before creating directories`() {
    val root = Files.createTempDirectory("managed-records")
    val store = store(root)
    assertFailsWith<InvalidManagedSkillRecordSchemaError> { store.recordPath("../escape") }
    assertEquals(false, Files.exists(root.resolve("managed-skills")))
  }

  @Test
  fun `rejects duplicate keys and a record name that differs from its path`() {
    val root = Files.createTempDirectory("managed-records")
    val store = store(root)
    val path = store.recordPath("expected")
    Files.createDirectories(path.parent)
    path.writeText("""{"contract_version":"0.1","name":"expected","name":"other"}""")
    assertFailsWith<InvalidManagedSkillRecordSchemaError> { store.read("expected") }
  }

  @Test
  fun `public path reads require the canonical managed record layout`() {
    val root = Files.createTempDirectory("managed-records")
    val store = store(root)
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
    val store = store(root)
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
    val store = store(root)
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
    val store = store(root)
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
    val store = store(root)
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

  @Test
  fun `refuses publication when atomic replacement is unavailable`() {
    val root = Files.createTempDirectory("managed-records")
    val store = FileManagedSkillRecordStore(root, atomicReplace = { source, target ->
      throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "unsupported")
    })
    val now = Instant.parse("2026-07-15T12:00:00Z")
    val record = ManagedSkillRecord(
      name = "sample-skill", sourceKind = ManagedSkillSourceKind.DIRECTORY,
      sourcePath = store.sourceRoot("sample-skill"), activeContentHash = "a".repeat(64),
      selectedTargets = setOf(AgentSkillTargetId("claude", root.resolve("claude").toAbsolutePath())),
      importedAt = now, updatedAt = now,
    )

    assertFailsWith<IllegalStateException> { store.write(record) }
    assertEquals(false, Files.exists(store.recordPath(record.name)))
  }

  @Test
  fun `forces the containing directory after atomic replacement`() {
    val root = Files.createTempDirectory("managed-records")
    var forcedDirectory: java.nio.file.Path? = null
    val store = FileManagedSkillRecordStore(root, forceDirectory = { forcedDirectory = it })
    val now = Instant.parse("2026-07-15T12:00:00Z")
    val record = ManagedSkillRecord(
      name = "sample-skill", sourceKind = ManagedSkillSourceKind.DIRECTORY,
      sourcePath = store.sourceRoot("sample-skill"), activeContentHash = "a".repeat(64),
      selectedTargets = setOf(AgentSkillTargetId("claude", root.resolve("claude").toAbsolutePath())),
      importedAt = now, updatedAt = now,
    )

    store.write(record)
    assertEquals(store.recordPath(record.name).parent, forcedDirectory)
  }

  private fun validWire(root: java.nio.file.Path, name: String): String = """{
    "contract_version":"0.1","name":"$name","source_kind":"directory",
    "source_path":"${root.resolve("managed-skills/$name/source")}",
    "active_content_hash":"${"a".repeat(64)}",
    "selected_targets":[{"provider":"codex","skills_path":"${root.resolve("codex")}"}],
    "imported_at":"2026-07-15T12:00:00Z","updated_at":"2026-07-15T12:00:00Z"
  }"""
}

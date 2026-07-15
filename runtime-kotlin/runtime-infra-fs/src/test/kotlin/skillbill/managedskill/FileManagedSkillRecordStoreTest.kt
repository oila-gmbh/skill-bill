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

class FileManagedSkillRecordStoreTest {
  @Test
  fun `round trips a validated record with multiple provider paths`() {
    val root = Files.createTempDirectory("managed-records")
    val store = FileManagedSkillRecordStore(root)
    val now = Instant.parse("2026-07-15T12:00:00Z")
    val record = ManagedSkillRecord(
      name = "sample-skill",
      sourceKind = ManagedSkillSourceKind.DIRECTORY,
      sourcePath = root.resolve("import"),
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
}

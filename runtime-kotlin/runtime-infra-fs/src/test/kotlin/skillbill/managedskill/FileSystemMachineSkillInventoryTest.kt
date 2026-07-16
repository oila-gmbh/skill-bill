package skillbill.managedskill

import skillbill.install.model.BaselineManifest
import skillbill.managedskill.model.AgentSkillTargetId
import skillbill.managedskill.model.MachineSkillOwnership
import skillbill.managedskill.model.ManagedSkillRecord
import skillbill.managedskill.model.ManagedSkillSourceKind
import skillbill.ports.install.baseline.BaselineManifestPersistencePort
import skillbill.ports.install.baseline.model.ReadBaselineManifestRequest
import skillbill.ports.install.baseline.model.ReadBaselineManifestResult
import skillbill.ports.install.baseline.model.WriteBaselineManifestRequest
import skillbill.ports.install.baseline.model.WriteBaselineManifestResult
import skillbill.ports.managedskill.model.InventoryTarget
import skillbill.ports.managedskill.model.ReadMachineSkillInventoryRequest
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemMachineSkillInventoryTest {
  @Test
  fun `groups same normalized name across multiple provider paths and reports divergence`() {
    val home = createTempDirectory("inventory-home")
    val first = home.resolve("profiles/one/skills").createDirectories()
    val second = home.resolve("profiles/two/skills").createDirectories()
    writeSkill(first.resolve("Demo"), "first")
    writeSkill(second.resolve("demo"), "second")
    val adapter = FileSystemMachineSkillInventory(emptyBaseline)
    val targets = listOf(first, second).map { path ->
      InventoryTarget(AgentSkillTargetId("claude", path), detected = true, selected = false, path.toString())
    }

    val snapshot = adapter.read(ReadMachineSkillInventoryRequest(home, targets, false))

    assertEquals(2, snapshot.targets.size)
    assertEquals(1, snapshot.rows.size)
    assertEquals("demo", snapshot.rows.single().normalizedName)
    assertEquals(MachineSkillOwnership.UNMANAGED, snapshot.rows.single().ownership)
    assertTrue(snapshot.rows.single().divergent)
    assertTrue(snapshot.rows.single().targetPresence.all { it.present })
    assertFalse(Files.exists(home.resolve(".skill-bill")))
  }

  @Test
  fun `product evidence excludes metadata names and bill prefix alone proves nothing`() {
    val home = createTempDirectory("inventory-products")
    val root = home.resolve("skills").createDirectories()
    writeSkill(root.resolve("product-without-prefix"), "product")
    writeSkill(root.resolve("bill-third-party"), "third party")
    val baseline = baselineWith("skills/product-without-prefix/content.md")
    val adapter = FileSystemMachineSkillInventory(baseline)
    val target = InventoryTarget(AgentSkillTargetId("codex", root), true, false, "Codex")

    val ordinary = adapter.read(ReadMachineSkillInventoryRequest(home, listOf(target), false))
    val diagnostic = adapter.read(ReadMachineSkillInventoryRequest(home, listOf(target), true))

    assertEquals(listOf("bill-third-party"), ordinary.rows.map { it.normalizedName })
    assertFalse(ordinary.productDiagnostics.isNotEmpty())
    assertEquals("product-without-prefix", diagnostic.productDiagnostics.single().rawName)
  }

  @Test
  fun `managed ownership requires the exact expected snapshot link`() {
    val home = createTempDirectory("inventory-managed")
    val root = home.resolve("agent/skills").createDirectories()
    val targetId = AgentSkillTargetId("codex", root)
    val store = FileManagedSkillRecordStore(home)
    val source = store.sourceRoot("managed-demo")
    writeSkill(source, "managed")
    val hash = OpaqueSkillBundleScanner().scan(source, emptySet()).contentHash
    val snapshot = store.snapshotRoot("managed-demo", hash)
    writeSkill(snapshot, "managed")
    Files.createSymbolicLink(root.resolve("managed-demo"), snapshot)
    val now = Instant.parse("2026-01-01T00:00:00Z")
    store.write(
      ManagedSkillRecord(
        "managed-demo",
        ManagedSkillSourceKind.DIRECTORY,
        source,
        hash,
        setOf(targetId),
        now,
        now,
      ),
      FileManagedSkillRecordStore.EXPECTED_ABSENT,
    )
    val adapter = FileSystemMachineSkillInventory(emptyBaseline)
    val target = InventoryTarget(targetId, true, true, "Codex")

    val snapshotResult = adapter.read(ReadMachineSkillInventoryRequest(home, listOf(target), false))

    assertEquals(MachineSkillOwnership.MANAGED, snapshotResult.rows.single().ownership)
    assertTrue(snapshotResult.diagnostics.none { it.kind == "CORRUPT_RECORD" })
  }

  private fun writeSkill(path: java.nio.file.Path, description: String) {
    path.createDirectories()
    path.resolve("SKILL.md").writeText(
      "---\nname: ${path.fileName.toString().lowercase()}\ndescription: $description\n---\n\n## Guidance\n\n$description\n",
    )
  }

  private fun baselineWith(path: String): BaselineManifestPersistencePort = object : BaselineManifestPersistencePort {
    override fun readBaseline(request: ReadBaselineManifestRequest) =
      ReadBaselineManifestResult(BaselineManifest.of(BaselineManifest.CONTRACT_VERSION, mapOf(path to "a".repeat(64))), true)
    override fun writeBaseline(request: WriteBaselineManifestRequest): WriteBaselineManifestResult = error("read only")
  }

  private val emptyBaseline = baselineWith("unrelated/path")
}

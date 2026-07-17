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
import kotlin.test.assertContains
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
  fun `product evidence covers base skills platform skills and managed pack views`() {
    val home = createTempDirectory("inventory-products")
    val root = home.resolve("skills").createDirectories()
    writeSkill(root.resolve("product-without-prefix"), "product")
    writeSkill(root.resolve("pack-product"), "pack product")
    writeSkill(root.resolve("bill-third-party"), "third party")
    root.resolve("platform-packs").createDirectories().resolve(".skill-bill-install").writeText("")
    val baseline = baselineWith(
      "skills/product-without-prefix/content.md",
      "platform-packs/kotlin/code-review/pack-product",
    )
    val adapter = FileSystemMachineSkillInventory(baseline)
    val target = InventoryTarget(AgentSkillTargetId("codex", root), true, false, "Codex")

    val ordinary = adapter.read(ReadMachineSkillInventoryRequest(home, listOf(target), false))
    val diagnostic = adapter.read(ReadMachineSkillInventoryRequest(home, listOf(target), true))

    assertEquals(listOf("bill-third-party"), ordinary.rows.map { it.normalizedName })
    assertFalse(ordinary.productDiagnostics.isNotEmpty())
    assertEquals(
      setOf("pack-product", "platform-packs", "product-without-prefix"),
      diagnostic.productDiagnostics.map { it.rawName }.toSet(),
    )
  }

  @Test
  fun `active product staging is excluded when baseline metadata is unavailable`() {
    val home = createTempDirectory("inventory-active-product")
    val root = home.resolve("skills").createDirectories()
    val hash = "a".repeat(16)
    val staged = home.resolve(".skill-bill/installed-skills/bill-product-$hash")
    writeSkill(staged, "product")
    staged.resolve(".content-hash").writeText(hash)
    Files.createSymbolicLink(root.resolve("bill-product"), staged)
    val target = InventoryTarget(AgentSkillTargetId("codex", root), true, false, "Codex")

    val result = FileSystemMachineSkillInventory(emptyBaseline).read(
      ReadMachineSkillInventoryRequest(home, listOf(target), true),
    )

    assertTrue(result.rows.isEmpty())
    assertEquals("bill-product", result.productDiagnostics.single().rawName)
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

  @Test
  fun `record without installed links remains visible as missing`() {
    val home = createTempDirectory("inventory-missing")
    val root = home.resolve("agent/skills").createDirectories()
    val targetId = AgentSkillTargetId("codex", root)
    val store = FileManagedSkillRecordStore(home)
    val source = store.sourceRoot("missing-demo")
    writeSkill(source, "managed")
    val hash = OpaqueSkillBundleScanner().scan(source, emptySet()).contentHash
    val now = Instant.parse("2026-01-01T00:00:00Z")
    val record = ManagedSkillRecord(
      "missing-demo",
      ManagedSkillSourceKind.DIRECTORY,
      source,
      hash,
      setOf(targetId),
      now,
      now,
    )
    store.write(record, FileManagedSkillRecordStore.EXPECTED_ABSENT)

    val result = FileSystemMachineSkillInventory(emptyBaseline).read(
      ReadMachineSkillInventoryRequest(home, listOf(InventoryTarget(targetId, true, true, "Codex")), false),
    )

    assertEquals("missing-demo", result.rows.single().normalizedName)
    assertEquals(skillbill.managedskill.model.MachineSkillHealth.MISSING, result.rows.single().health)
    assertFalse(result.rows.single().targetPresence.single().present)
  }

  @Test
  fun `scan failures are corrupt facts and external staging lookalikes are not product`() {
    val home = createTempDirectory("inventory-invalid")
    val root = home.resolve("agent/skills").createDirectories()
    root.resolve("malformed").createDirectories().resolve("not-skill.txt").writeText("x")
    val external = home.resolve("external/lookalike-hash").createDirectories()
    external.resolve(".content-hash").writeText("hash")
    Files.createSymbolicLink(root.resolve("lookalike"), external)
    val target = InventoryTarget(AgentSkillTargetId("codex", root), true, false, "Codex")

    val result = FileSystemMachineSkillInventory(emptyBaseline).read(
      ReadMachineSkillInventoryRequest(home, listOf(target), true),
    )

    assertTrue(result.productDiagnostics.isEmpty())
    assertEquals(setOf("lookalike", "malformed"), result.rows.map { it.normalizedName }.toSet())
    assertContains(result.rows.single { it.normalizedName == "malformed" }.issues.map { it.code }, "INVALID_BUNDLE")
  }

  @Test
  fun `unstable expected leaf and linked source do not prove healthy ownership`() {
    val home = createTempDirectory("inventory-untrusted")
    val root = home.resolve("agent/skills").createDirectories()
    val targetId = AgentSkillTargetId("codex", root)
    val store = FileManagedSkillRecordStore(home)
    val externalSource = home.resolve("external/source")
    writeSkill(externalSource, "external")
    val sourceLink = store.sourceRoot("managed-demo")
    sourceLink.parent.createDirectories()
    Files.createSymbolicLink(sourceLink, externalSource)
    val hash = OpaqueSkillBundleScanner().scan(externalSource, emptySet()).contentHash
    val expected = store.snapshotRoot("managed-demo", hash)
    expected.parent.createDirectories()
    Files.createSymbolicLink(expected, externalSource)
    Files.createSymbolicLink(root.resolve("managed-demo"), expected)
    val now = Instant.parse("2026-01-01T00:00:00Z")
    val record = ManagedSkillRecord(
      "managed-demo",
      ManagedSkillSourceKind.DIRECTORY,
      sourceLink,
      hash,
      setOf(targetId),
      now,
      now,
    )
    store.write(record, FileManagedSkillRecordStore.EXPECTED_ABSENT)

    val result = FileSystemMachineSkillInventory(emptyBaseline).read(
      ReadMachineSkillInventoryRequest(home, listOf(InventoryTarget(targetId, true, true, "Codex")), false),
    )

    assertEquals(MachineSkillOwnership.CONFLICT, result.rows.single().ownership)
    assertContains(result.rows.single().issues.map { it.code }, "INVALID_SOURCE")
  }

  @Test
  fun `invalid state root is reported loudly`() {
    val home = createTempDirectory("inventory-state")
    val targetRoot = home.resolve("agent/skills").createDirectories()
    Files.createSymbolicLink(home.resolve(".skill-bill"), home.resolve("elsewhere"))

    val result = FileSystemMachineSkillInventory(emptyBaseline).read(
      ReadMachineSkillInventoryRequest(
        home,
        listOf(InventoryTarget(AgentSkillTargetId("codex", targetRoot), true, false, "Codex")),
        false,
      ),
    )

    assertContains(result.diagnostics.map { it.kind }, "INVALID_STATE_ROOT")
  }

  private fun writeSkill(path: java.nio.file.Path, description: String) {
    path.createDirectories()
    path.resolve("SKILL.md").writeText(
      "---\nname: ${path.fileName.toString().lowercase()}\n" +
        "description: $description\n---\n\n## Guidance\n\n$description\n",
    )
  }

  private fun baselineWith(vararg paths: String): BaselineManifestPersistencePort =
    object : BaselineManifestPersistencePort {
      override fun readBaseline(request: ReadBaselineManifestRequest) = ReadBaselineManifestResult(
        BaselineManifest.of(BaselineManifest.CONTRACT_VERSION, paths.associateWith { "a".repeat(64) }),
        true,
      )

      override fun writeBaseline(request: WriteBaselineManifestRequest): WriteBaselineManifestResult =
        error("read only")
    }

  private val emptyBaseline = baselineWith("unrelated/path")
}

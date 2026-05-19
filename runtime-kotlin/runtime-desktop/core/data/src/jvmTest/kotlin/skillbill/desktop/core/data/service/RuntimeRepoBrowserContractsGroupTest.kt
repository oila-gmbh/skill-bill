package skillbill.desktop.core.data.service

import skillbill.desktop.core.domain.model.RenderRunState
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.scaffold.PlatformPackSchemaPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKILL-47 F-002: end-to-end test for the read-only Contracts group surfaced by
 * `RuntimeRepoBrowserService.buildTree`. The existing
 * `PlatformPackSchemaViewerStateTest` exercises the VM with a hand-built tree
 * and a fake authoring gateway, which means it does NOT prove that
 * `buildTree` actually emits the Contracts group from the production code
 * path. This test does:
 *
 * - Seeds a temp repo containing the canonical schema at
 *   `orchestration/contracts/platform-pack-schema.yaml`.
 * - Opens the repo through the real `RuntimeRepoBrowserService`.
 * - Asserts the resulting tree carries a top-level "Contracts" group whose
 *   single child is a `TreeItemKind.CONTRACT` leaf with `editable=false`,
 *   `readOnlyLabel` set, and the leaf's `authoredPath` pointing at the
 *   canonical schema path (sourced from `PlatformPackSchemaPaths`).
 * - Asserts the selection detail loaded from `describeSelection` reports a
 *   read-only document whose bytes equal the schema file on disk.
 *
 * Also asserts the Contracts group is omitted when the canonical schema is
 * absent — the loader treats the file as the single source of truth and
 * does not synthesize a row from thin air.
 */
class RuntimeRepoBrowserContractsGroupTest {
  @Test
  fun `Contracts group exposes platform-pack schema row with canonical path`() {
    val repo = seedRepoWithSchema("contracts-group")
    val service = RuntimeRepoBrowserService()

    val session = service.open(repo.toString())
    assertEquals(RepoLoadState.LOADED, session.loadStatus.state)

    val tree = service.treeFor(session)
    val contractsGroup = tree.singleOrNull { it.label == "Contracts" }
    assertNotNull(contractsGroup, "Expected a top-level 'Contracts' group in the tree.")
    assertEquals(TreeItemKind.GROUP, contractsGroup.kind)
    assertFalse(contractsGroup.editable)

    val schemaLeaf = contractsGroup.children.single()
    assertEquals("Platform pack schema", schemaLeaf.label)
    assertEquals(TreeItemKind.CONTRACT, schemaLeaf.kind)
    assertFalse(schemaLeaf.editable)
    assertEquals("RO", schemaLeaf.readOnlyLabel)
    assertEquals(
      PlatformPackSchemaPaths.REPO_RELATIVE_PATH,
      schemaLeaf.authoredPath,
      "Schema leaf must point at the canonical schema path (single source of truth).",
    )

    val placeholder = service.describeSelection(schemaLeaf.id)
    assertFalse(placeholder.editable, "Contract row must render read-only.")
    val schemaBytesOnDisk = Files.readString(repo.resolve(PlatformPackSchemaPaths.REPO_RELATIVE_PATH))
    assertEquals(
      schemaBytesOnDisk,
      placeholder.content,
      "Schema viewer must read from the canonical on-disk file (no second copy).",
    )
  }

  @Test
  fun `render on a contract row is a no-op that does not throw`() {
    // F-001 regression: SkillBillViewModel.beginRender() (single-target) is NOT filtered by
    // `isRenderableTreeItemKind()`, so an end-user pressing Render on the read-only Contracts
    // row reaches `RuntimeRepoBrowserService.render` with kind="contract". Previously this hit
    // the `else -> error(...)` branch in `renderDetail` and surfaced a runtime crash. The fix
    // adds an explicit "contract" branch that returns an empty `RenderOutcome` so the failure
    // mode is clean rather than silent.
    val repo = seedRepoWithSchema("contracts-group-render")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val schemaLeaf = service.treeFor(session).single { it.label == "Contracts" }.children.single()

    val summary = service.render(session, schemaLeaf.id)

    assertEquals(RenderRunState.PASSED, summary.state, "Render on a contract row must succeed as a no-op.")
    assertTrue(summary.blocks.isEmpty(), "Contract render must produce zero blocks.")
    assertTrue(summary.generatedArtifacts.isEmpty(), "Contract render must produce zero generated artifacts.")
    assertNull(summary.runtimeExceptionName, "Contract render must not surface a runtime exception name.")
    assertNull(summary.runtimeExceptionMessage, "Contract render must not surface a runtime exception message.")
  }

  @Test
  fun `Contracts group is omitted when canonical schema file is absent`() {
    val repo = seedRepoWithoutSchema("contracts-group-missing-schema")
    val service = RuntimeRepoBrowserService()

    val session = service.open(repo.toString())
    assertEquals(RepoLoadState.LOADED, session.loadStatus.state)

    val tree = service.treeFor(session)
    assertTrue(
      tree.none { it.label == "Contracts" },
      "Contracts group must be omitted when the canonical schema file is missing.",
    )
  }

  private fun seedRepoWithSchema(name: String): Path {
    val repo = seedRepoWithoutSchema(name)
    val contractsRoot = repo.resolve(PlatformPackSchemaPaths.REPO_RELATIVE_PATH).parent
    Files.createDirectories(contractsRoot)
    // Use the real on-disk schema so a future schema reshape automatically updates this test
    // (single source of truth — same file the runtime parser uses).
    val canonical = repoRootFromTest().resolve(PlatformPackSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(canonical), "Canonical schema missing at $canonical.")
    Files.writeString(
      repo.resolve(PlatformPackSchemaPaths.REPO_RELATIVE_PATH),
      Files.readString(canonical),
    )
    return repo
  }

  private fun seedRepoWithoutSchema(name: String): Path {
    val repo = Files.createTempDirectory("skillbill-desktop-$name")
    val skillDir = repo.resolve("skills/bill-alpha")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
        |---
        |name: bill-alpha
        |description: Alpha description.
        |---
        |
        |# alpha
        |
        |Alpha guidance.
      """.trimMargin(),
    )
    return repo
  }

  private fun repoRootFromTest(): Path {
    var current = Path.of("").toAbsolutePath().normalize()
    while (current.parent != null) {
      val hasSettings = Files.isRegularFile(current.resolve("runtime-kotlin/settings.gradle.kts"))
      val hasContracts = Files.isDirectory(current.resolve("orchestration/contracts"))
      if (hasSettings && hasContracts) {
        return current
      }
      current = current.parent
    }
    error("Could not locate skill-bill repo root from ${Path.of("").toAbsolutePath().normalize()}")
  }
}

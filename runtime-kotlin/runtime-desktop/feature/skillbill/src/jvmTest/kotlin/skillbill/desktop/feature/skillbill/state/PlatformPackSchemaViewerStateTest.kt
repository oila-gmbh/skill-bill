package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.AuthoredContentDocument
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.SkillBillTreeItemMetadata
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.PrPublishingGateway
import skillbill.desktop.core.domain.service.RenderGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.desktop.core.domain.service.ValidationGateway
import skillbill.desktop.core.testing.FakeAuthoringGateway
import skillbill.desktop.core.testing.FakeDesktopPreferenceStore
import skillbill.desktop.core.testing.FakeGitGateway
import skillbill.desktop.core.testing.FakePrPublishingGateway
import skillbill.desktop.core.testing.FakeRecentRepoRepository
import skillbill.desktop.core.testing.FakeRenderGateway
import skillbill.desktop.core.testing.FakeRepoSessionService
import skillbill.desktop.core.testing.FakeSkillTreeService
import skillbill.desktop.core.testing.FakeValidationGateway
import skillbill.scaffold.PlatformPackSchemaPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SKILL-47 AC8 — fourth bullet: state-snapshot UI test for the read-only
 * platform-pack schema viewer. Verifies:
 *
 * - The Skill Bill tree carries a top-level "Contracts" group whose single
 *   leaf is the "Platform pack schema" row.
 * - Selecting the row produces an EditorPlaceholder with editable=false and
 *   the canonical file's bytes loaded byte-for-byte from disk.
 * - Switching away from a dirty skill back to the contract leaf does not
 *   carry the previous selection's dirty state across — per-selection
 *   keyed state stays isolated.
 *
 * Follows the FakeSkillBillServices + SkillBillViewModelTest harness
 * (state-snapshot, NOT ComposeTestRule). The fake tree mirrors what
 * `RuntimeRepoBrowserService.buildTree` emits at runtime; the fake
 * authoring gateway returns the actual on-disk schema bytes so we test
 * that the viewer reads from the canonical file.
 */
class PlatformPackSchemaViewerStateTest {

  @Test
  fun `tree contains Contracts group with Platform pack schema leaf`() {
    val (_, schemaBytes) = canonicalSchemaFileAndBytes()
    val viewModel = newViewModel(
      skillTreeService = FakeSkillTreeService(treeWithContractsGroup()),
      authoringGateway = contractAuthoringGateway(schemaBytes),
    )
    viewModel.selectRepoPath("/repo")

    val state = viewModel.state()
    val contractsGroup = state.treeItems.singleOrNull { it.label == "Contracts" }
    assertNotNull(contractsGroup, "Expected a top-level 'Contracts' group in the tree.")
    assertEquals(TreeItemKind.GROUP, contractsGroup.kind)
    val schemaLeaf = contractsGroup.children.single()
    assertEquals("Platform pack schema", schemaLeaf.label)
    assertEquals(TreeItemKind.CONTRACT, schemaLeaf.kind)
    assertFalse(schemaLeaf.editable)
  }

  @Test
  fun `selecting the schema leaf produces a read-only editor with on-disk bytes`() {
    val (_, schemaBytes) = canonicalSchemaFileAndBytes()
    val viewModel = newViewModel(
      skillTreeService = FakeSkillTreeService(treeWithContractsGroup()),
      authoringGateway = contractAuthoringGateway(schemaBytes),
    )
    viewModel.selectRepoPath("/repo")

    val state = viewModel.selectTreeItem(CONTRACT_LEAF_ID)

    assertEquals(CONTRACT_LEAF_ID, state.selectedTreeItemId)
    assertFalse(state.editor.editable, "Schema viewer must render read-only.")
    assertEquals("contract", state.editor.kind)
    assertEquals(schemaBytes, state.editor.content, "Viewer must show the on-disk schema bytes verbatim.")
    assertFalse(state.editor.dirty, "Fresh selection must not carry dirty state.")
  }

  @Test
  fun `switching from a dirty skill back to the schema leaf does not carry dirty state`() {
    val (_, schemaBytes) = canonicalSchemaFileAndBytes()
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "saved\n")
      // Pre-stage the contract document on the same gateway so the VM's
      // describeSelection/loadDocument calls return the canonical bytes
      // when the schema row is selected.
      documentsByTreeItemId[CONTRACT_LEAF_ID] = AuthoredContentDocument(
        treeItemId = CONTRACT_LEAF_ID,
        title = "Platform pack schema",
        skillName = null,
        kind = "contract",
        authoredPath = SCHEMA_REPO_RELATIVE_PATH,
        text = schemaBytes,
        editable = false,
        readOnlyReason = SCHEMA_READ_ONLY_REASON,
      )
    }
    val viewModel = newViewModel(
      skillTreeService = FakeSkillTreeService(treeWithContractsGroupAndSkill()),
      authoringGateway = authoringGateway,
    )
    viewModel.selectRepoPath("/repo")

    // Load the editable skill, mark it dirty, then discard the prompt and
    // jump to the contract leaf. The contract editor must come up clean.
    viewModel.selectTreeItem("skill-one")
    viewModel.updateEditorDraft("dirty draft\n")
    viewModel.selectTreeItem(CONTRACT_LEAF_ID)
    val discarded = viewModel.discardDirtyEditorPrompt()

    assertEquals(CONTRACT_LEAF_ID, discarded.selectedTreeItemId)
    assertFalse(discarded.editor.dirty)
    assertEquals(schemaBytes, discarded.editor.content)
    assertFalse(discarded.editor.editable)
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private fun canonicalSchemaFileAndBytes(): Pair<Path, String> {
    val repoRoot = repoRootFromTest()
    val schemaFile = repoRoot.resolve(SCHEMA_REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema is missing at $schemaFile.")
    return schemaFile to Files.readString(schemaFile)
  }

  private fun contractAuthoringGateway(schemaBytes: String): AuthoringGateway = FakeAuthoringGateway().apply {
    documentsByTreeItemId[CONTRACT_LEAF_ID] = AuthoredContentDocument(
      treeItemId = CONTRACT_LEAF_ID,
      title = "Platform pack schema",
      skillName = null,
      kind = "contract",
      authoredPath = SCHEMA_REPO_RELATIVE_PATH,
      text = schemaBytes,
      editable = false,
      readOnlyReason = SCHEMA_READ_ONLY_REASON,
    )
  }

  private fun treeWithContractsGroup(): List<SkillBillTreeItem> = listOf(
    SkillBillTreeItem(
      id = "contracts-group",
      label = "Contracts",
      kind = TreeItemKind.GROUP,
      editable = false,
      children = listOf(
        SkillBillTreeItem(
          id = CONTRACT_LEAF_ID,
          label = "Platform pack schema",
          kind = TreeItemKind.CONTRACT,
          editable = false,
          readOnlyLabel = "RO",
          authoredPath = SCHEMA_REPO_RELATIVE_PATH,
          status = "read-only",
          metadata = SkillBillTreeItemMetadata(kind = "contract"),
        ),
      ),
    ),
  )

  private fun treeWithContractsGroupAndSkill(): List<SkillBillTreeItem> = listOf(
    SkillBillTreeItem(
      id = "skills-group",
      label = "Horizontal Skills",
      kind = TreeItemKind.GROUP,
      children = listOf(
        SkillBillTreeItem(
          id = "skill-one",
          label = "skill-one",
          kind = TreeItemKind.SKILL,
        ),
      ),
    ),
  ) + treeWithContractsGroup()

  private fun newViewModel(
    repoSessionService: RepoSessionService = FakeRepoSessionService(),
    skillTreeService: SkillTreeService,
    authoringGateway: AuthoringGateway,
    gitGateway: GitGateway = FakeGitGateway(),
    prPublishingGateway: PrPublishingGateway = FakePrPublishingGateway(),
    validationGateway: ValidationGateway = FakeValidationGateway(),
    renderGateway: RenderGateway = FakeRenderGateway(),
    recentRepoRepository: FakeRecentRepoRepository = FakeRecentRepoRepository(),
  ): SkillBillViewModel = SkillBillViewModel(
    repoSessionService = repoSessionService,
    skillTreeService = skillTreeService,
    authoringGateway = authoringGateway,
    gitGateway = gitGateway,
    prPublishingGateway = prPublishingGateway,
    validationGateway = validationGateway,
    renderGateway = renderGateway,
    recentRepoRepository = recentRepoRepository,
    scaffoldGateway = skillbill.desktop.core.testing.scaffold.FakeScaffoldGateway(),
    firstRunGateway = defaultFirstRunGateway(),
    desktopPreferenceStore = FakeDesktopPreferenceStore(
      initialFirstRunPreferences = skillbill.desktop.core.datastore.DesktopFirstRunPreferences(completed = true),
    ),
    skillRemoveGateway = skillbill.desktop.core.testing.skillremove.FakeSkillRemoveGateway(),
  )

  private fun defaultFirstRunGateway(): skillbill.desktop.core.domain.service.DesktopFirstRunGateway =
    skillbill.desktop.core.testing.install.FakeDesktopFirstRunGateway(
      discoveryResult = skillbill.desktop.core.domain.model.FirstRunDiscoveryResult.Success(
        skillbill.desktop.core.domain.model.FirstRunSetupDiscovery(
          agents = emptyList(),
          platformPacks = emptyList(),
        ),
      ),
      planResult = skillbill.desktop.core.domain.model.FirstRunPlanResult.Failed("not scripted"),
      applyResult = skillbill.desktop.core.domain.model.FirstRunApplyResult.Failed(
        skillbill.desktop.core.domain.model.FirstRunInstallOutcome(
          status = skillbill.desktop.core.domain.model.FirstRunInstallStatus.FAILURE,
          title = "not scripted",
        ),
      ),
    )

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

  companion object {
    private const val CONTRACT_LEAF_ID = "contract:platform-pack-schema"

    // SKILL-47 F-008: single source of truth — the runtime path constant.
    private val SCHEMA_REPO_RELATIVE_PATH = PlatformPackSchemaPaths.REPO_RELATIVE_PATH
    private val SCHEMA_READ_ONLY_REASON =
      "Runtime contract — edit ${PlatformPackSchemaPaths.REPO_RELATIVE_PATH} in code to change."
  }
}

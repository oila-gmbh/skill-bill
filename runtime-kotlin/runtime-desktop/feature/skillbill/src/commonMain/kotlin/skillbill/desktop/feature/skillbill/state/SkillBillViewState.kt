package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.AuthoredContentDocument
import skillbill.desktop.core.domain.model.ConfirmDeletionState
import skillbill.desktop.core.domain.model.DirtyEditorPrompt
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.FirstRunSetupState
import skillbill.desktop.core.domain.model.MachineToolsState
import skillbill.desktop.core.domain.model.PartialMutationPostMortem
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.ScaffoldWizardState
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.model.SkillBillStatusBar
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.SkillBillTreeItemMetadata
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.model.ValidateAgentConfigsSummary
import skillbill.desktop.core.domain.model.WorkListState
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.ManagedMachineSkillEditPresentation

internal class SkillBillViewState(
  private val authoringGateway: AuthoringGateway,
  initialFirstRunSetup: FirstRunSetupState?,
) {
  var installedWorkspaceRoot: String? = null
  var normalizedInstalledWorkspaceRoot: String? = null
  var repoPathText: String = ""
  var currentSession: RepoSession? = null
  var repositoryTreeItems: List<SkillBillTreeItem> = emptyList()
  val treeItems: List<SkillBillTreeItem>
    get() = repositoryTreeItems + machineSkillsRoot()
  var selectedTreeItemId: String? = null
  var expandedNodeIds: Set<String> = emptySet()
  var busyOperation: SkillBillBusyOperation? = null
  var activeOperationToken = 0L
  var loadedEditorDocument: AuthoredContentDocument? = null
  var managedEditorBase: ManagedMachineSkillEditPresentation? = null
  var machineEditorDetail: skillbill.desktop.core.domain.model.MachineSkillManagerDetail? = null
  var editorSelectionId: String? = null
  var editorDraftText: String = ""
  var editorSaveInProgress: Boolean = false
  var editorSaveErrorMessage: String? = null
  var dirtyEditorPrompt: DirtyEditorPrompt? = null
  var activeSaveToken: Long = 0L
  var commandPaletteOpen: Boolean = false
  var commandPaletteQuery: String = ""
  var commandPaletteSelectedResultIndex: Int = 0
  var scaffoldWizard: ScaffoldWizardState? = null
  var activeScaffoldToken: Long = 0L
  var nextScaffoldBaselineLayerRowId: Long = 1L

  var confirmDeletion: ConfirmDeletionState? = null

  var partialMutationPostMortem: PartialMutationPostMortem? = null
  var activeRemovalToken: Long = 0L
  var validateAgentConfigsSummary: ValidateAgentConfigsSummary =
    ValidateAgentConfigsSummary.empty
  var activeValidateAgentConfigsToken: Long = 0L
  var firstRunSetup: FirstRunSetupState? = initialFirstRunSetup
  var activeFirstRunToken: Long = 0L
  var startupRequested: Boolean = false
  var workList: WorkListState = WorkListState()
  var activeWorkListRequestToken: Long = 0L
  var machineTools: MachineToolsState = MachineToolsState()

  var currentState = createState()

  fun createState(): SkillBillState {
    val session = currentSession
    val resolvedTreeItemId = selectedTreeItemId?.takeIf(::containsTreeItem)
    val editor = editorForSelection(resolvedTreeItemId)
    val state = SkillBillState(
      selectedRepoPath = session?.repoPath,
      repoPathText = repoPathText,
      repoStatus = session?.loadStatus ?: RepoLoadStatus.empty,
      treeItems = treeItems,
      selectedTreeItemId = resolvedTreeItemId,
      expandedNodeIds = expandedNodeIds,
      busyOperation = busyOperation,
      editor = editor,
      statusBar = statusBarFor(session?.repoPath, editor),
      canReturnToInstalledWorkspace =
      installedWorkspaceRoot != null && !isInstalledWorkspaceRoot(session?.repoPath),
      dirtyEditorPrompt = dirtyEditorPrompt,
      workList = workList,
      machineTools = machineTools,
    )
    val paletteState = buildCommandPaletteState(
      state = state,
      open = commandPaletteOpen,
      query = commandPaletteQuery,
      selectedResultIndex = commandPaletteSelectedResultIndex,
    )
    commandPaletteSelectedResultIndex = paletteState.selectedResultIndex
    val suggestedBaselineLayer = scaffoldWizard?.let(::suggestedBaselineLayer)
    val wizardState = scaffoldWizard?.copy(
      dirtyRepoWarning = false,
      baselineLayerSuggestion = suggestedBaselineLayer?.form,
      baselineLayerSuggestionLabel = suggestedBaselineLayer?.label,
    )
    return state.copy(
      commandPalette = paletteState,
      scaffoldWizard = wizardState,
      firstRunSetup = firstRunSetup,
      confirmDeletion = confirmDeletion,
      validateAgentConfigs = validateAgentConfigsSummary,
      partialMutationPostMortem = partialMutationPostMortem,
    )
  }

  fun isInstalledWorkspaceRoot(repoPath: String?): Boolean {
    val normalizedRoot = normalizedInstalledWorkspaceRoot ?: return false
    val candidate = repoPath?.takeIf { it.isNotBlank() }?.let(::normalizeRepoPath) ?: return false
    return candidate == normalizedRoot
  }

  fun ancestorIdsOf(itemId: String): Set<String> {
    val ancestors = mutableSetOf<String>()
    fun visit(items: List<SkillBillTreeItem>, parents: List<String>): Boolean {
      for (item in items) {
        if (item.id == itemId) {
          ancestors.addAll(parents)
          return true
        }
        if (item.children.isNotEmpty() && visit(item.children, parents + item.id)) {
          return true
        }
      }
      return false
    }
    visit(treeItems, emptyList())
    return ancestors
  }

  private fun editorForSelection(resolvedTreeItemId: String?): EditorPlaceholder {
    val base = resolvedTreeItemId?.let(authoringGateway::describeSelection) ?: EditorPlaceholder.empty
    val document = loadedEditorDocument
      ?.takeIf { editorSelectionId == resolvedTreeItemId }
    val dirty = isEditorDirty()
    return if (document == null) {
      base.copy(
        saveInProgress = editorSaveInProgress,
        saveErrorMessage = editorSaveErrorMessage,
      )
    } else {
      base.copy(
        title = document.title,
        skillName = document.skillName,
        kind = document.kind,
        authoredPath = document.authoredPath,
        editable = document.editable,
        readOnlyLabel = if (document.editable) null else base.readOnlyLabel ?: "RO",
        content = document.text,
        draftContent = editorDraftText,
        dirty = dirty,
        saveInProgress = editorSaveInProgress,
        saveErrorMessage = editorSaveErrorMessage ?: document.runtimeErrorMessage,
        readOnlyReason = document.readOnlyReason,
        machineSkillDetail = machineEditorDetail,
      )
    }
  }

  fun loadEditorForSelection() {
    managedEditorBase = null
    machineEditorDetail = null
    val selection = selectedTreeItemId
    if (selection == null || !containsTreeItem(selection)) {
      resetEditorDocument()
      return
    }
    val document = if (selection.startsWith("$MACHINE_SKILLS_ROOT_ID:skill:")) {
      val logicalKey = selection.substringAfterLast(":skill:")
      machineTools.manager.rows.firstOrNull { it.logicalKey == logicalKey }?.let { row ->
        val guidance = if (row.health.contains("DIVERG", true)) {
          "Choose an installed agent copy to inspect or adopt from Manage installed skills."
        } else {
          "Use Manage installed skills to inspect occurrences or adopt this skill."
        }
        AuthoredContentDocument(
          selection,
          row.name,
          row.name,
          "Third-party runtime skill",
          null,
          "${row.description}\n\n$guidance",
          false,
          guidance,
        )
      } ?: AuthoredContentDocument(
        selection,
        logicalKey,
        logicalKey,
        "Third-party runtime skill",
        null,
        "Loading machine skill details…",
        false,
        "Machine inventory details are loading.",
      )
    } else {
      authoringGateway.loadDocument(currentSession, selection)
    }
    loadedEditorDocument = document
    editorSelectionId = selection
    editorDraftText = document.text
    editorSaveInProgress = false
    editorSaveErrorMessage = document.runtimeErrorMessage
    dirtyEditorPrompt = null
  }

  fun loadMachineEditorDocument(
    document: AuthoredContentDocument,
    managedEdit: ManagedMachineSkillEditPresentation? = null,
    detail: skillbill.desktop.core.domain.model.MachineSkillManagerDetail? = null,
  ) {
    if (document.treeItemId != selectedTreeItemId) return
    loadedEditorDocument = document
    editorSelectionId = document.treeItemId
    editorDraftText = document.text
    editorSaveInProgress = false
    editorSaveErrorMessage = document.runtimeErrorMessage
    dirtyEditorPrompt = null
    managedEditorBase = managedEdit
    machineEditorDetail = detail
    currentState = createState()
  }

  fun refreshMachineEditorDetail(detail: skillbill.desktop.core.domain.model.MachineSkillManagerDetail?) {
    machineEditorDetail = detail
    currentState = createState()
  }

  fun resetEditorDocument() {
    loadedEditorDocument = null
    managedEditorBase = null
    machineEditorDetail = null
    editorSelectionId = null
    editorDraftText = ""
    editorSaveInProgress = false
    editorSaveErrorMessage = null
    dirtyEditorPrompt = null
  }

  fun isEditorDirty(): Boolean {
    val document = loadedEditorDocument ?: return false
    return document.editable && editorDraftText != document.text
  }

  private fun statusBarFor(repoPath: String?, editor: EditorPlaceholder): SkillBillStatusBar = SkillBillStatusBar(
    targetCount = repositoryTreeItems.flatten().count { it.children.isEmpty() },
    repoPathLabel = repoPath ?: "no repo",
    readOnlyModeLabel = when {
      isEditorDirty() -> "dirty"
      editor.editable -> SkillBillStatusBar.EDITABLE_MODE_LABEL
      else -> SkillBillStatusBar.READ_ONLY_MODE_LABEL
    },
    policyLabel = SkillBillStatusBar.POLICY_LABEL,
  )

  fun containsTreeItem(itemId: String): Boolean = treeItems.flatten().any { item -> item.id == itemId }

  private fun machineSkillsRoot(): SkillBillTreeItem {
    val manager = machineTools.manager
    val rows = manager.rows.distinctBy { it.logicalKey }
    val children = when {
      manager.loading -> listOf(
        machinePlaceholder("loading", "Loading third-party skills…"),
      ) + rows.map(::machineSkillItem)
      manager.error != null -> listOf(
        machinePlaceholder("error", manager.error ?: "Inventory failed"),
      ) + rows.map(::machineSkillItem)
      rows.isEmpty() -> listOf(machinePlaceholder("empty", "No third-party skills found"))
      else -> rows.map(::machineSkillItem)
    }
    return SkillBillTreeItem(
      id = MACHINE_SKILLS_ROOT_ID,
      label = "Third-Party Skills",
      kind = TreeItemKind.GROUP,
      status = rows.size.toString(),
      editable = false,
      children = children,
      external = true,
    )
  }

  private fun machineSkillItem(row: skillbill.desktop.core.domain.model.MachineSkillManagerRow): SkillBillTreeItem {
    val status = machineSkillStatus(row.ownership, row.health, row.divergent)
    val editable = status == "managed" && row.health.equals("HEALTHY", ignoreCase = true)
    return SkillBillTreeItem(
      id = "$MACHINE_SKILLS_ROOT_ID:skill:${row.logicalKey}",
      label = row.name,
      kind = TreeItemKind.SKILL,
      status = status,
      editable = editable,
      readOnlyLabel = if (editable) null else "RO",
      metadata = SkillBillTreeItemMetadata(
        skillName = row.logicalKey,
        kind = "Third-party runtime skill",
        description = row.description,
        supportedAgents = row.agents.sorted(),
      ),
      external = true,
    )
  }

  private fun machinePlaceholder(id: String, label: String) = SkillBillTreeItem(
    id = "$MACHINE_SKILLS_ROOT_ID:$id",
    label = label,
    kind = TreeItemKind.PLACEHOLDER,
    editable = false,
    external = true,
  )

  private fun machineSkillStatus(ownership: String, health: String, divergent: Boolean): String = when {
    ownership.equals("CONFLICT", ignoreCase = true) -> "conflict"
    divergent || health.contains("DIVERG", ignoreCase = true) -> "divergent"
    health.contains("BROKEN", ignoreCase = true) || health.contains("MISSING", ignoreCase = true) ||
      health.contains("CORRUPT", ignoreCase = true) || health.contains("MISMATCH", ignoreCase = true) ||
      health.contains("ORPHAN", ignoreCase = true) -> "broken"
    ownership.equals("MANAGED", ignoreCase = true) -> "managed"
    else -> "unmanaged"
  }

  fun describe(error: Throwable): String {
    val message = error.message
    val name = error::class.simpleName ?: error::class.qualifiedName ?: "Throwable"
    return if (message.isNullOrBlank()) name else "$name: $message"
  }
}

internal const val MACHINE_SKILLS_ROOT_ID = "machine:third-party-skills"

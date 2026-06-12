package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.AuthoredContentDocument
import skillbill.desktop.core.domain.model.ConfirmDeletionState
import skillbill.desktop.core.domain.model.DirtyEditorPrompt
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.FirstRunSetupState
import skillbill.desktop.core.domain.model.PartialMutationPostMortem
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.ScaffoldWizardState
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.model.SkillBillStatusBar
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.ValidateAgentConfigsSummary
import skillbill.desktop.core.domain.service.AuthoringGateway

internal class SkillBillViewState(
  private val authoringGateway: AuthoringGateway,
  initialFirstRunSetup: FirstRunSetupState?,
) {
  var installedWorkspaceRoot: String? = null
  var normalizedInstalledWorkspaceRoot: String? = null
  var repoPathText: String = ""
  var currentSession: RepoSession? = null
  var treeItems: List<SkillBillTreeItem> = emptyList()
  var selectedTreeItemId: String? = null
  var expandedNodeIds: Set<String> = emptySet()
  var busyOperation: SkillBillBusyOperation? = null
  var activeOperationToken = 0L
  var loadedEditorDocument: AuthoredContentDocument? = null
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
      )
    }
  }

  fun loadEditorForSelection() {
    val selection = selectedTreeItemId
    if (selection == null || !containsTreeItem(selection)) {
      resetEditorDocument()
      return
    }
    val document = authoringGateway.loadDocument(currentSession, selection)
    loadedEditorDocument = document
    editorSelectionId = selection
    editorDraftText = document.text
    editorSaveInProgress = false
    editorSaveErrorMessage = document.runtimeErrorMessage
    dirtyEditorPrompt = null
  }

  fun resetEditorDocument() {
    loadedEditorDocument = null
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
    targetCount = treeItems.flatten().count { it.children.isEmpty() },
    repoPathLabel = repoPath ?: "no repo",
    readOnlyModeLabel = when {
      isEditorDirty() -> "dirty"
      editor.editable -> SkillBillStatusBar.EDITABLE_MODE_LABEL
      else -> SkillBillStatusBar.READ_ONLY_MODE_LABEL
    },
    policyLabel = SkillBillStatusBar.POLICY_LABEL,
  )

  fun containsTreeItem(itemId: String): Boolean = treeItems.flatten().any { item -> item.id == itemId }

  fun describe(error: Throwable): String {
    val message = error.message
    val name = error::class.simpleName ?: error::class.qualifiedName ?: "Throwable"
    return if (message.isNullOrBlank()) name else "$name: $message"
  }
}

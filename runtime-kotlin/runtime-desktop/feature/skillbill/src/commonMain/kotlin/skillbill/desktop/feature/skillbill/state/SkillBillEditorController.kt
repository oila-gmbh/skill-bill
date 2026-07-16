package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.AuthoringSaveResult
import skillbill.desktop.core.domain.model.DirtyEditorPromptReason
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.service.AuthoringGateway

internal class SkillBillEditorController(
  private val state: SkillBillViewState,
  private val repoController: SkillBillRepoController,
  private val authoringGateway: AuthoringGateway,
) {
  fun updateEditorDraft(text: String): SkillBillState = with(state) {
    val document = loadedEditorDocument
    if (
      document?.editable != true ||
      editorSaveInProgress ||
      busyOperation != null
    ) {
      currentState = createState()
      return currentState
    }
    editorDraftText = text
    editorSaveErrorMessage = null
    currentState = createState()
    currentState
  }

  fun revertEditorDraft(): SkillBillState = with(state) {
    if (editorSaveInProgress) {
      currentState = createState()
      return currentState
    }
    loadEditorForSelection()
    editorSaveErrorMessage = null
    dirtyEditorPrompt = null
    currentState = createState()
    currentState
  }

  fun beginSaveEditor(): EditorSaveRequest? = with(state) {
    val document = loadedEditorDocument
    if (
      document?.editable != true ||
      !isEditorDirty() ||
      editorSaveInProgress ||
      busyOperation != null
    ) {
      currentState = createState()
      return null
    }
    activeSaveToken += 1
    editorSaveInProgress = true
    editorSaveErrorMessage = null
    busyOperation = SkillBillBusyOperation.SAVE
    currentState = createState()
    EditorSaveRequest(
      token = activeSaveToken,
      session = currentSession,
      treeItemId = selectedTreeItemId,
      body = editorDraftText,
      managedEdit = managedEditorBase?.copy(markdown = editorDraftText),
    )
  }

  fun runSaveEditor(request: EditorSaveRequest): EditorSaveResult {
    val result = runCatching {
      authoringGateway.saveDocument(request.session, request.treeItemId, request.body)
    }.getOrElse { error -> AuthoringSaveResult.failed(state.describe(error)) }
    return EditorSaveResult(request = request, result = result)
  }

  fun finishSaveEditor(result: EditorSaveResult): SkillBillState = with(state) {
    if (result.request.token != activeSaveToken) {
      return currentState
    }
    editorSaveInProgress = false
    busyOperation = null
    if (result.result.success) {
      val savedDocument = result.result.document
        ?: authoringGateway.loadDocument(currentSession, selectedTreeItemId)
      loadedEditorDocument = savedDocument
      editorSelectionId = result.request.treeItemId
      editorDraftText = savedDocument.text
      editorSaveErrorMessage = null
      dirtyEditorPrompt = null
      if (savedDocument.kind == "skill-bill config") {
        repoController.openRepo(currentSession?.repoPath ?: repoPathText, preserveSelection = true)
      }
    } else {
      editorSaveErrorMessage = result.result.runtimeErrorMessage ?: "Save failed."
    }
    currentState = createState()
    currentState
  }

  fun cancelDirtyEditorPrompt(): SkillBillState = with(state) {
    dirtyEditorPrompt = null
    currentState = createState()
    currentState
  }

  fun discardDirtyEditorPrompt(): SkillBillState = with(state) {
    val prompt = dirtyEditorPrompt ?: return currentState
    dirtyEditorPrompt = null
    when (prompt.reason) {
      DirtyEditorPromptReason.SELECTION_CHANGE -> {
        prompt.targetTreeItemId
          ?.let { target -> repoController.selectTreeItemIgnoringDirty(target) }
          ?: run {
            selectedTreeItemId = null
            resetEditorDocument()
          }
      }
      DirtyEditorPromptReason.REFRESH -> {
        resetEditorDocument()
        repoController.beginRefresh()
      }
      DirtyEditorPromptReason.REPO_SWITCH -> {
        resetEditorDocument()
        prompt.targetRepoPath?.let { repoController.beginSelectRepoPath(it) }
      }
      DirtyEditorPromptReason.RETURN_TO_INSTALLED_WORKSPACE -> {
        resetEditorDocument()
        repoController.beginReturnToInstalledWorkspace()
      }
      DirtyEditorPromptReason.CHOOSE_DIRECTORY -> {
        resetEditorDocument()
        repoController.busyState(SkillBillBusyOperation.CHOOSE_DIRECTORY)
      }
    }
    currentState = createState()
    currentState
  }
}

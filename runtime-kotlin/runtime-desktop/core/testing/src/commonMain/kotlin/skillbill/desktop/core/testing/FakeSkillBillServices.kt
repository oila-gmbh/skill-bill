package skillbill.desktop.core.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import skillbill.desktop.core.datastore.DesktopFirstRunPreferences
import skillbill.desktop.core.datastore.DesktopPreferenceStore
import skillbill.desktop.core.domain.model.AuthoredContentDocument
import skillbill.desktop.core.domain.model.AuthoringSaveResult
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.RecentRepoRepository
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService

class FakeRepoSessionService : RepoSessionService {
  override fun open(repoPath: String): RepoSession = RepoSession(
    repoPath = repoPath,
    isRecognizedSkillBillRepo = true,
    loadStatus = RepoLoadStatus(
      state = RepoLoadState.LOADED,
      message = "Loaded",
      skillCount = 1,
    ),
  )
}

class FakeSkillTreeService(
  private val items: List<SkillBillTreeItem>,
  var generatedArtifactIdsByPath: Map<String, String> = emptyMap(),
) : SkillTreeService {
  /**
   * F-004-TESTING: counter incremented every time [treeFor] is called. Tests assert this to prove
   * that a Success removal triggered exactly one tree re-scan via `beginRefreshAfterScaffold` /
   * `loadRepo` and a Failed removal did not.
   */
  var refreshCount: Int = 0
    private set

  override fun treeFor(session: RepoSession?): List<SkillBillTreeItem> {
    refreshCount += 1
    return items
  }

  @Suppress("UNUSED_PARAMETER")
  override fun resolveGeneratedArtifactTreeItemId(session: RepoSession?, artifactPath: String): String? =
    generatedArtifactIdsByPath[artifactPath]
}

class FakeAuthoringGateway : AuthoringGateway {
  var documentsByTreeItemId: MutableMap<String, AuthoredContentDocument> = mutableMapOf()
  var saveFailureMessage: String? = null
  var loadCallCount: Int = 0
    private set
  var saveCallCount: Int = 0
    private set
  var lastSavedTreeItemId: String? = null
    private set
  var lastSavedBody: String? = null
    private set

  override fun describeSelection(treeItemId: String): EditorPlaceholder =
    documentsByTreeItemId[treeItemId]?.toEditorPlaceholder()
      ?: EditorPlaceholder(title = treeItemId, detail = "Selected $treeItemId")

  override fun loadDocument(session: RepoSession?, treeItemId: String?): AuthoredContentDocument {
    loadCallCount += 1
    return treeItemId?.let(documentsByTreeItemId::get)
      ?: AuthoredContentDocument(
        treeItemId = treeItemId,
        title = treeItemId ?: "No source selected",
        skillName = null,
        kind = null,
        authoredPath = null,
        text = "",
        editable = false,
        readOnlyReason = "No editable governed source is selected.",
      )
  }

  override fun saveDocument(session: RepoSession?, treeItemId: String?, body: String): AuthoringSaveResult {
    saveCallCount += 1
    lastSavedTreeItemId = treeItemId
    lastSavedBody = body
    saveFailureMessage?.let { message -> return AuthoringSaveResult.failed(message) }
    val current = treeItemId?.let(documentsByTreeItemId::get)
      ?: return AuthoringSaveResult.failed("No editable governed source is selected.")
    if (!current.editable) {
      return AuthoringSaveResult.failed(current.readOnlyReason ?: "This selection cannot enter editable mode.")
    }
    val updated = current.copy(text = body, runtimeErrorMessage = null)
    documentsByTreeItemId[treeItemId] = updated
    return AuthoringSaveResult(success = true, document = updated)
  }

  fun putDocument(treeItemId: String, text: String, editable: Boolean = true, readOnlyReason: String? = null) {
    documentsByTreeItemId[treeItemId] = AuthoredContentDocument(
      treeItemId = treeItemId,
      title = treeItemId,
      skillName = treeItemId,
      kind = if (editable) "horizontal skill" else "generated artifact",
      authoredPath = "skills/$treeItemId/content.md",
      text = text,
      editable = editable,
      readOnlyReason = readOnlyReason,
    )
  }
}

class FakeDesktopPreferenceStore(
  initialRepoPath: String? = null,
  initialFirstRunPreferences: DesktopFirstRunPreferences = DesktopFirstRunPreferences(),
) : DesktopPreferenceStore {
  private val recentRepoPathState = MutableStateFlow(initialRepoPath)
  private val firstRunState = MutableStateFlow(initialFirstRunPreferences)

  override val recentRepoPath: StateFlow<String?>
    get() = recentRepoPathState

  override val firstRunPreferences: StateFlow<DesktopFirstRunPreferences>
    get() = firstRunState

  override fun rememberRepoPath(repoPath: String) {
    recentRepoPathState.value = repoPath.trim().takeIf(String::isNotEmpty)
  }

  override fun clearRecentRepoPath() {
    recentRepoPathState.value = null
  }

  override fun saveFirstRunPreferences(preferences: DesktopFirstRunPreferences) {
    firstRunState.value = preferences
  }

  override fun markFirstRunCompleted(preferences: DesktopFirstRunPreferences) {
    firstRunState.value = preferences.copy(completed = true)
  }
}

class FakeRecentRepoRepository(initialRepoPath: String? = null) : RecentRepoRepository {
  private var repoPath: String? = initialRepoPath

  override fun recentRepoPath(): String? = repoPath

  override fun rememberRepoPath(repoPath: String) {
    this.repoPath = repoPath.trim().takeIf(String::isNotEmpty)
  }

  override fun clearRecentRepoPath() {
    repoPath = null
  }
}

private fun AuthoredContentDocument.toEditorPlaceholder(): EditorPlaceholder = EditorPlaceholder(
  title = title,
  detail = readOnlyReason ?: "Authored Skill Bill source.",
  skillName = skillName,
  kind = kind,
  authoredPath = authoredPath,
  editable = editable,
  readOnlyLabel = if (editable) null else "RO",
  content = text,
  draftContent = text,
  readOnlyReason = readOnlyReason,
)

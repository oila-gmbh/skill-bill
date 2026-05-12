package skillbill.desktop.core.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import skillbill.desktop.core.datastore.DesktopPreferenceStore
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RenderSummary
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.SourceControlStatus
import skillbill.desktop.core.domain.model.ValidationSummary
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.RecentRepoRepository
import skillbill.desktop.core.domain.service.RenderGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.desktop.core.domain.service.ValidationGateway

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

class FakeSkillTreeService(private val items: List<SkillBillTreeItem>) : SkillTreeService {
  override fun treeFor(session: RepoSession?): List<SkillBillTreeItem> = items
}

class FakeAuthoringGateway : AuthoringGateway {
  override fun describeSelection(treeItemId: String): EditorPlaceholder =
    EditorPlaceholder(title = treeItemId, detail = "Selected $treeItemId")
}

class FakeGitGateway : GitGateway {
  override fun statusFor(session: RepoSession?): SourceControlStatus =
    SourceControlStatus(branchLabel = "main", summary = session?.repoPath.orEmpty())
}

class FakeValidationGateway(
  var scriptedSummary: ValidationSummary = ValidationSummary.unavailable,
  var resolveBySourcePath: Map<String, String> = emptyMap(),
  var throwOnValidate: Throwable? = null,
) : ValidationGateway {
  var validateCallCount: Int = 0
    private set

  @Suppress("UNUSED_PARAMETER")
  override fun validate(session: RepoSession?): ValidationSummary {
    validateCallCount += 1
    throwOnValidate?.let { throw it }
    return scriptedSummary
  }

  @Suppress("UNUSED_PARAMETER")
  override fun resolveTreeItemIdForSource(session: RepoSession?, sourcePath: String): String? =
    resolveBySourcePath[sourcePath]
}

class FakeRenderGateway(
  var scriptedSummary: RenderSummary = RenderSummary.unavailable,
  var throwOnRender: Throwable? = null,
) : RenderGateway {
  var callCount: Int = 0
    private set

  var lastRequestedTreeItemId: String? = null
    private set

  @Suppress("UNUSED_PARAMETER")
  override fun render(session: RepoSession?, treeItemId: String): RenderSummary {
    callCount += 1
    lastRequestedTreeItemId = treeItemId
    throwOnRender?.let { throw it }
    return scriptedSummary
  }
}

class FakeDesktopPreferenceStore(initialRepoPath: String? = null) : DesktopPreferenceStore {
  private val recentRepoPathState = MutableStateFlow(initialRepoPath)

  override val recentRepoPath: StateFlow<String?>
    get() = recentRepoPathState

  override fun rememberRepoPath(repoPath: String) {
    recentRepoPathState.value = repoPath.trim().takeIf(String::isNotEmpty)
  }

  override fun clearRecentRepoPath() {
    recentRepoPathState.value = null
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

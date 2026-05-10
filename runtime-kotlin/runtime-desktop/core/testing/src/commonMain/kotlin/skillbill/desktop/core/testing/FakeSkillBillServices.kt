package skillbill.desktop.core.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import skillbill.desktop.core.datastore.DesktopPreferenceStore
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.SourceControlStatus
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.RecentRepoRepository
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService

class FakeRepoSessionService : RepoSessionService {
  override fun open(repoPath: String): RepoSession = RepoSession(repoPath = repoPath, isRecognizedSkillBillRepo = true)
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

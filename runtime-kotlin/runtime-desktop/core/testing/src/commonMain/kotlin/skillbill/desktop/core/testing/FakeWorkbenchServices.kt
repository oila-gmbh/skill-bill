package skillbill.desktop.core.testing

import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SourceControlStatus
import skillbill.desktop.core.domain.model.WorkbenchTreeItem
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService

class FakeRepoSessionService : RepoSessionService {
  override fun open(repoPath: String): RepoSession = RepoSession(repoPath = repoPath, isRecognizedSkillBillRepo = true)
}

class FakeSkillTreeService(private val items: List<WorkbenchTreeItem>) : SkillTreeService {
  override fun treeFor(session: RepoSession?): List<WorkbenchTreeItem> = items
}

class FakeAuthoringGateway : AuthoringGateway {
  override fun describeSelection(treeItemId: String): EditorPlaceholder =
    EditorPlaceholder(title = treeItemId, detail = "Selected $treeItemId")
}

class FakeGitGateway : GitGateway {
  override fun statusFor(session: RepoSession?): SourceControlStatus =
    SourceControlStatus(branchLabel = "main", summary = session?.repoPath.orEmpty())
}

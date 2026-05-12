package skillbill.desktop.core.domain.service

import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RenderSummary
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.SourceControlStatus
import skillbill.desktop.core.domain.model.ValidationSummary

interface RepoSessionService {
  fun open(repoPath: String): RepoSession
}

interface RecentRepoRepository {
  fun recentRepoPath(): String?
  fun rememberRepoPath(repoPath: String)
  fun clearRecentRepoPath()
}

interface SkillTreeService {
  fun treeFor(session: RepoSession?): List<SkillBillTreeItem>
}

interface AuthoringGateway {
  fun describeSelection(treeItemId: String): EditorPlaceholder
}

interface GitGateway {
  fun statusFor(session: RepoSession?): SourceControlStatus
}

interface ValidationGateway {
  fun validate(session: RepoSession?): ValidationSummary

  fun resolveTreeItemIdForSource(session: RepoSession?, sourcePath: String): String?
}

interface RenderGateway {
  fun render(session: RepoSession?, treeItemId: String): RenderSummary
}

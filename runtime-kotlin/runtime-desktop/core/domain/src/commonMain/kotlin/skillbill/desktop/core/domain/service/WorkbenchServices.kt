package skillbill.desktop.core.domain.service

import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SourceControlStatus
import skillbill.desktop.core.domain.model.WorkbenchTreeItem

interface RepoSessionService {
  fun open(repoPath: String): RepoSession
}

interface SkillTreeService {
  fun treeFor(session: RepoSession?): List<WorkbenchTreeItem>
}

interface AuthoringGateway {
  fun describeSelection(treeItemId: String): EditorPlaceholder
}

interface GitGateway {
  fun statusFor(session: RepoSession?): SourceControlStatus
}

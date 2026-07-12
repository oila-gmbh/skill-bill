package skillbill.desktop.core.domain.service

import skillbill.desktop.core.domain.model.AuthoredContentDocument
import skillbill.desktop.core.domain.model.AuthoringSaveResult
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillTreeItem

interface RepoSessionService {
  fun open(repoPath: String): RepoSession
}

interface WorkListGateway {
  fun list(): List<skillbill.desktop.core.domain.model.DesktopWorkItem>
}

object EmptyWorkListGateway : WorkListGateway {
  override fun list(): List<skillbill.desktop.core.domain.model.DesktopWorkItem> = emptyList()
}

interface RecentRepoRepository {
  suspend fun recentRepoPath(): String?
  suspend fun rememberRepoPath(repoPath: String)
  suspend fun clearRecentRepoPath()
}

interface SkillTreeService {
  fun treeFor(session: RepoSession?): List<SkillBillTreeItem>

  fun resolveGeneratedArtifactTreeItemId(session: RepoSession?, artifactPath: String): String? = null
}

interface AuthoringGateway {
  fun describeSelection(treeItemId: String): EditorPlaceholder

  fun loadDocument(session: RepoSession?, treeItemId: String?): AuthoredContentDocument {
    val described = treeItemId?.let(::describeSelection)
    return AuthoredContentDocument(
      treeItemId = treeItemId,
      title = described?.title ?: treeItemId ?: "No source selected",
      skillName = described?.skillName,
      kind = described?.kind,
      authoredPath = described?.authoredPath,
      text = described?.content.orEmpty(),
      editable = described?.editable == true,
      readOnlyReason = described?.readOnlyReason ?: "This selection cannot enter editable mode.",
    )
  }

  fun saveDocument(session: RepoSession?, treeItemId: String?, body: String): AuthoringSaveResult =
    AuthoringSaveResult.failed("This selection cannot enter editable mode.")
}

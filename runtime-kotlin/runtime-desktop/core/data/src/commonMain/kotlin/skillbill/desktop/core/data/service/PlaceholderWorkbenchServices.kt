package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SourceControlStatus
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.model.WorkbenchTreeItem
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService

@Inject
class PlaceholderRepoSessionService : RepoSessionService {
  override fun open(repoPath: String): RepoSession = RepoSession(
    repoPath = repoPath,
    isRecognizedSkillBillRepo = false,
  )
}

@Inject
class PlaceholderSkillTreeService : SkillTreeService {
  override fun treeFor(session: RepoSession?): List<WorkbenchTreeItem> = listOf(
    WorkbenchTreeItem(
      id = "horizontal-skills",
      label = "Horizontal Skills",
      kind = TreeItemKind.GROUP,
      children = listOf(
        WorkbenchTreeItem(
          id = "horizontal-skills-placeholder",
          label = placeholderLabel(session),
          kind = TreeItemKind.PLACEHOLDER,
        ),
      ),
    ),
    WorkbenchTreeItem(
      id = "platform-packs",
      label = "Platform Packs",
      kind = TreeItemKind.GROUP,
      children = listOf(
        WorkbenchTreeItem(
          id = "platform-packs-placeholder",
          label = "Pack discovery will use runtime services.",
          kind = TreeItemKind.PLACEHOLDER,
        ),
      ),
    ),
    WorkbenchTreeItem(
      id = "repository",
      label = "Repository",
      kind = TreeItemKind.GROUP,
      children = listOf(
        WorkbenchTreeItem(
          id = "repository-placeholder",
          label = "Repo metadata will appear after browser integration.",
          kind = TreeItemKind.PLACEHOLDER,
        ),
      ),
    ),
  )

  private fun placeholderLabel(session: RepoSession?): String = if (session == null) {
    "Open a repository to load authored sources."
  } else {
    "Discovery is not connected yet for ${session.repoPath}."
  }
}

@Inject
class PlaceholderAuthoringGateway : AuthoringGateway {
  override fun describeSelection(treeItemId: String): EditorPlaceholder = EditorPlaceholder(
    title = "Editor placeholder",
    detail = "Selection `$treeItemId` is tracked in memory. Authoring is not enabled yet.",
  )
}

@Inject
class PlaceholderGitGateway : GitGateway {
  override fun statusFor(session: RepoSession?): SourceControlStatus = if (session == null) {
    SourceControlStatus.empty
  } else {
    SourceControlStatus(
      branchLabel = "Repository not validated",
      summary = "Placeholder session for ${session.repoPath}. No repository files are read or written.",
    )
  }
}

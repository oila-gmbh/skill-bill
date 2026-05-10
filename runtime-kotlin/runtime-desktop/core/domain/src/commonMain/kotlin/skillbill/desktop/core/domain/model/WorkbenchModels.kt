package skillbill.desktop.core.domain.model

data class RepoSession(
  val repoPath: String,
  val isRecognizedSkillBillRepo: Boolean,
)

data class UserSession(
  val id: String,
  val displayName: String,
) {
  companion object {
    val localDesktop: UserSession =
      UserSession(
        id = "local-desktop",
        displayName = "Local Desktop",
      )
  }
}

data class WorkbenchState(
  val selectedRepoPath: String?,
  val treeItems: List<WorkbenchTreeItem>,
  val selectedTreeItemId: String?,
  val editor: EditorPlaceholder,
  val sourceControl: SourceControlStatus,
)

data class WorkbenchTreeItem(
  val id: String,
  val label: String,
  val kind: TreeItemKind,
  val children: List<WorkbenchTreeItem> = emptyList(),
)

enum class TreeItemKind {
  GROUP,
  PLACEHOLDER,
}

data class EditorPlaceholder(
  val title: String,
  val detail: String,
) {
  companion object {
    val empty: EditorPlaceholder =
      EditorPlaceholder(
        title = "No source selected",
        detail = "Open a Skill Bill repository, then choose a skill or pack source.",
      )
  }
}

data class SourceControlStatus(
  val branchLabel: String,
  val summary: String,
) {
  companion object {
    val empty: SourceControlStatus =
      SourceControlStatus(
        branchLabel = "No repository",
        summary = "Open a local checkout before source control state is available.",
      )
  }
}

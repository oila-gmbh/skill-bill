package skillbill.desktop.core.domain.model

data class RepoSession(
  val repoPath: String,
  val isRecognizedSkillBillRepo: Boolean,
  val loadStatus: RepoLoadStatus = RepoLoadStatus.empty,
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

data class SkillBillState(
  val selectedRepoPath: String?,
  val repoStatus: RepoLoadStatus = RepoLoadStatus.empty,
  val treeItems: List<SkillBillTreeItem>,
  val selectedTreeItemId: String?,
  val editor: EditorPlaceholder,
  val sourceControl: SourceControlStatus,
)

data class SkillBillTreeItem(
  val id: String,
  val label: String,
  val kind: TreeItemKind,
  val authoredPath: String? = null,
  val status: String? = null,
  val editable: Boolean = true,
  val metadata: SkillBillTreeItemMetadata? = null,
  val children: List<SkillBillTreeItem> = emptyList(),
)

enum class TreeItemKind {
  GROUP,
  SKILL,
  PLATFORM_PACK,
  ADD_ON,
  NATIVE_AGENT,
  GENERATED_ARTIFACT,
  PLACEHOLDER,
}

data class SkillBillTreeItemMetadata(
  val skillName: String? = null,
  val kind: String? = null,
  val packageName: String? = null,
  val platform: String? = null,
  val family: String? = null,
  val area: String? = null,
)

data class RepoLoadStatus(
  val state: RepoLoadState,
  val message: String,
  val issueCount: Int = 0,
  val skillCount: Int = 0,
  val addonCount: Int = 0,
  val platformPackCount: Int = 0,
  val nativeAgentCount: Int = 0,
  val issues: List<String> = emptyList(),
) {
  companion object {
    val empty: RepoLoadStatus =
      RepoLoadStatus(
        state = RepoLoadState.EMPTY,
        message = "Open a local Skill Bill checkout.",
      )
  }
}

enum class RepoLoadState {
  EMPTY,
  LOADED,
  INVALID,
}

data class GeneratedArtifactDetail(
  val path: String,
  val reason: String,
)

data class EditorPlaceholder(
  val title: String,
  val detail: String,
  val skillName: String? = null,
  val kind: String? = null,
  val authoredPath: String? = null,
  val status: String? = null,
  val editable: Boolean = false,
  val content: String? = null,
  val generatedArtifacts: List<GeneratedArtifactDetail> = emptyList(),
) {
  companion object {
    val empty: EditorPlaceholder =
      EditorPlaceholder(
        title = "No source selected",
        detail = "Open a SkillBill repository, then choose a skill or pack source.",
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

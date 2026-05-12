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
  val repoPathText: String = selectedRepoPath.orEmpty(),
  val repoStatus: RepoLoadStatus = RepoLoadStatus.empty,
  val treeItems: List<SkillBillTreeItem>,
  val selectedTreeItemId: String?,
  val expandedNodeIds: Set<String> = emptySet(),
  val busyOperation: SkillBillBusyOperation? = null,
  val editor: EditorPlaceholder,
  val sourceControl: SourceControlStatus,
  val statusBar: SkillBillStatusBar = SkillBillStatusBar.empty,
  val validation: ValidationSummary = ValidationSummary.unavailable,
)

enum class ValidationSeverity {
  ERROR,
  WARNING,
  INFO,
}

data class ValidationIssue(
  val severity: ValidationSeverity,
  val code: String?,
  val message: String,
  val sourcePath: String?,
  val exceptionName: String? = null,
)

enum class ValidationRunState {
  RUNNING,
  PASSED,
  FAILED,
  UNAVAILABLE,
}

data class ValidationSummary(
  val state: ValidationRunState,
  val issues: List<ValidationIssue> = emptyList(),
  val errorCount: Int = issues.count { it.severity == ValidationSeverity.ERROR },
  val warningCount: Int = issues.count { it.severity == ValidationSeverity.WARNING },
  val infoCount: Int = issues.count { it.severity == ValidationSeverity.INFO },
  val runtimeExceptionName: String? = null,
  val runtimeExceptionMessage: String? = null,
) {
  val totalCount: Int = errorCount + warningCount + infoCount

  companion object {
    val unavailable: ValidationSummary = ValidationSummary(state = ValidationRunState.UNAVAILABLE)
  }
}

data class SkillBillTreeItem(
  val id: String,
  val label: String,
  val kind: TreeItemKind,
  val authoredPath: String? = null,
  val status: String? = null,
  val editable: Boolean = true,
  val readOnlyLabel: String? = null,
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

enum class SkillBillBusyOperation {
  OPEN_REPO,
  REFRESH,
  CHOOSE_DIRECTORY,
  VALIDATE,
}

data class SkillBillStatusBar(
  val targetCount: Int,
  val repoPathLabel: String,
  val branchLabel: String,
  val readOnlyModeLabel: String,
  val policyLabel: String,
) {
  companion object {
    val empty: SkillBillStatusBar =
      SkillBillStatusBar(
        targetCount = 0,
        repoPathLabel = "no repo",
        branchLabel = SourceControlStatus.empty.branchLabel,
        readOnlyModeLabel = READ_ONLY_MODE_LABEL,
        policyLabel = POLICY_LABEL,
      )

    const val READ_ONLY_MODE_LABEL = "read-only"
    const val POLICY_LABEL = "strict"
  }
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
  val readOnlyLabel: String? = null,
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

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
  val render: RenderSummary = RenderSummary.unavailable,
  val activeDockTab: DockTab = DockTab.Validation,
  val renderable: Boolean = false,
  val changes: ChangesSnapshot = ChangesSnapshot.empty,
  val changesBusy: Boolean = false,
  val selectedChangedFile: ChangedFile? = null,
  val selectedDiff: String = "",
  val selectedDiffBusy: Boolean = false,
  val history: List<CommitEntry> = emptyList(),
  val historyBusy: Boolean = false,
  val historyErrorMessage: String? = null,
  val historyPathFilter: String? = null,
  val commitMessage: String = "",
  val canCommit: Boolean = false,
  val commitBusy: Boolean = false,
  val commitErrorMessage: String? = null,
  val commitValidationFailed: Boolean = false,
  val commitValidationRunning: Boolean = false,
  val pushTarget: GitPushTarget? = null,
  val aheadBehind: GitAheadBehind? = null,
  val compareUrl: String? = null,
  val pushBusy: Boolean = false,
  val pushErrorMessage: String? = null,
  val pushStatusErrorMessage: String? = null,
  val canonicalPushConfirmationRequired: Boolean = false,
  val dirtyEditorPrompt: DirtyEditorPrompt? = null,
  val commandPalette: CommandPaletteState = CommandPaletteState(),
)

enum class DockTab {
  Validation,
  Changes,
  History,
  Console,
}

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
  RENDER,
  SAVE,
}

data class CommandPaletteState(
  val open: Boolean = false,
  val query: String = "",
  val selectedResultIndex: Int = 0,
  val results: List<CommandPaletteResult> = emptyList(),
)

data class CommandPaletteResult(
  val id: String,
  val title: String,
  val subtitle: String,
  val marker: String,
  val kind: CommandPaletteResultKind,
  val action: CommandPaletteAction,
  val treeItemId: String? = null,
  val disabledReason: String? = null,
  val rank: Int = 0,
) {
  val enabled: Boolean
    get() = disabledReason == null
}

enum class CommandPaletteResultKind {
  COMMAND,
  TREE_ITEM,
}

enum class CommandPaletteAction {
  SELECT_TREE_ITEM,
  OPEN_REPOSITORY,
  REFRESH,
  VALIDATE,
  RENDER,
  SHOW_CHANGES,
  SHOW_HISTORY,
  SAVE,
  REFRESH_GIT_STATUS,
}

enum class RenderRunState {
  UNAVAILABLE,
  RUNNING,
  PASSED,
  FAILED,
}

data class RenderBlock(
  val header: String,
  val content: String,
)

data class RenderSummary(
  val state: RenderRunState,
  val blocks: List<RenderBlock> = emptyList(),
  val generatedArtifacts: List<GeneratedArtifactDetail> = emptyList(),
  val durationMillis: Long = 0L,
  val runtimeExceptionName: String? = null,
  val runtimeExceptionMessage: String? = null,
) {
  companion object {
    val unavailable: RenderSummary = RenderSummary(state = RenderRunState.UNAVAILABLE)
  }
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
  val draftContent: String? = content,
  val dirty: Boolean = false,
  val saveInProgress: Boolean = false,
  val saveErrorMessage: String? = null,
  val readOnlyReason: String? = null,
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

data class AuthoredContentDocument(
  val treeItemId: String?,
  val title: String,
  val skillName: String?,
  val kind: String?,
  val authoredPath: String?,
  val text: String,
  val editable: Boolean,
  val readOnlyReason: String? = null,
  val runtimeErrorMessage: String? = null,
)

data class AuthoringSaveResult(
  val success: Boolean,
  val document: AuthoredContentDocument? = null,
  val runtimeErrorMessage: String? = null,
) {
  companion object {
    fun failed(message: String): AuthoringSaveResult =
      AuthoringSaveResult(success = false, runtimeErrorMessage = message)
  }
}

data class DirtyEditorPrompt(
  val reason: DirtyEditorPromptReason,
  val targetTreeItemId: String? = null,
  val targetRepoPath: String? = null,
)

enum class DirtyEditorPromptReason {
  SELECTION_CHANGE,
  REFRESH,
  REPO_SWITCH,
  CHOOSE_DIRECTORY,
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

enum class ChangedFileGroup {
  STAGED,
  UNSTAGED,
  UNTRACKED,
  GENERATED,
}

data class ChangedFile(
  val path: String,
  val group: ChangedFileGroup,
  // Raw porcelain status code, e.g. "M", "A", "??". Preserved for UI labels.
  val statusCode: String,
  // Whether this file is a generated artifact (cannot be opened in editable mode).
  val isGenerated: Boolean = group == ChangedFileGroup.GENERATED,
)

data class CommitEntry(
  val shortHash: String,
  val fullHash: String,
  val author: String,
  // ISO-8601 commit date, e.g. "2025-04-30T14:22:00+00:00".
  val isoDate: String,
  val subject: String,
  val changedPaths: List<String> = emptyList(),
)

data class ChangesSnapshot(
  val files: List<ChangedFile> = emptyList(),
  // Branch label captured atomically with the changed-file groups so the UI never needs to call
  // back into the gateway to populate the branch label on the UI thread (F-C701).
  val branchLabel: String = "",
  // When non-null, indicates the most recent gateway error message without mutating the rest of state.
  val errorMessage: String? = null,
  // F-A02: when true, this snapshot represents a stage/unstage failure that produced no fresh
  // file list. The VM merges the errorMessage into its existing snapshot instead of replacing files.
  val isFailed: Boolean = false,
) {
  companion object {
    val empty: ChangesSnapshot = ChangesSnapshot()

    // F-A02 helper: stage/unstage failures return only an error message and a `isFailed = true`
    // marker. The VM overlays this onto its existing snapshot to preserve the prior file list.
    fun failed(errorMessage: String): ChangesSnapshot = ChangesSnapshot(
      files = emptyList(),
      branchLabel = "",
      errorMessage = errorMessage,
      isFailed = true,
    )
  }
}

data class GitOperationResult(
  val success: Boolean,
  val errorMessage: String? = null,
) {
  companion object {
    val success: GitOperationResult = GitOperationResult(success = true)

    fun failed(errorMessage: String): GitOperationResult =
      GitOperationResult(success = false, errorMessage = errorMessage)
  }
}

data class GitAheadBehind(
  val ahead: Int,
  val behind: Int,
)

data class GitPushTarget(
  val remoteName: String,
  val branchName: String,
  val expectedCurrentBranch: String = branchName,
  val displayName: String = "$remoteName/$branchName",
  val isLikelyCanonical: Boolean = false,
  val canonicalWarning: String? = null,
)

data class GitPublishingStatus(
  val pushTarget: GitPushTarget? = null,
  val aheadBehind: GitAheadBehind? = null,
  val compareUrl: String? = null,
  val errorMessage: String? = null,
) {
  companion object {
    val empty: GitPublishingStatus = GitPublishingStatus()
  }
}

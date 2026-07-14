package skillbill.desktop.core.domain.model

import org.jetbrains.compose.resources.StringResource

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
  val statusBar: SkillBillStatusBar = SkillBillStatusBar.empty,
  val canReturnToInstalledWorkspace: Boolean = false,
  val dirtyEditorPrompt: DirtyEditorPrompt? = null,
  val commandPalette: CommandPaletteState = CommandPaletteState(),
  val scaffoldWizard: ScaffoldWizardState? = null,
  val firstRunSetup: FirstRunSetupState? = null,
  /** SKILL-46: non-null while the tree-context-menu Delete dialog is on screen. */
  val confirmDeletion: ConfirmDeletionState? = null,
  /** SKILL-46 AC8: post-delete output of scripts/validate_agent_configs piped into the dock console. */
  val validateAgentConfigs: ValidateAgentConfigsSummary = ValidateAgentConfigsSummary.empty,
  /**
   * F-CROSS-REPO-LOCK: separate slot from [confirmDeletion] so a partial-mutation post-mortem
   * (rollback failed) survives a stale-token finish, a dialog dismiss, AND a repo switch. Only an
   * explicit user acknowledgement clears the slot. The route renders this as a top-level banner.
   */
  val partialMutationPostMortem: PartialMutationPostMortem? = null,
  val workList: WorkListState = WorkListState(),
)

data class DesktopWorkItem(
  val issueKey: String?,
  val workflowKind: String,
  val workflowId: String,
  val startedAt: String,
  val currentState: String,
  val stateEnteredAt: String,
  val stateEnteredAtEstimated: Boolean,
) {
  val identity: DesktopWorkItemIdentity
    get() = DesktopWorkItemIdentity(workflowKind, workflowId, issueKey)
}

data class DesktopWorkItemIdentity(
  val workflowKind: String,
  val workflowId: String,
  val issueKey: String?,
) {
  val stableValue: String
    get() = listOf(workflowKind, workflowId, issueKey).joinToString("|") { it.identitySegment() }
}

private fun String?.identitySegment(): String = this?.let { "${it.length}:$it" } ?: "-1:"

enum class WorkListLoadState { COLLAPSED, LOADING, POPULATED, EMPTY, ERROR }

data class WorkListState(
  val expanded: Boolean = false,
  val loadState: WorkListLoadState = WorkListLoadState.COLLAPSED,
  val items: List<DesktopWorkItem> = emptyList(),
  val errorMessage: String? = null,
)

data class SkillBillTreeItem(
  val id: String,
  val label: String,
  val kind: TreeItemKind,
  val authoredPath: String? = null,
  val status: String? = null,
  val editable: Boolean = true,
  val readOnlyLabel: String? = null,
  val metadata: SkillBillTreeItemMetadata? = null,
  val baselineModified: Boolean = false,
  val children: List<SkillBillTreeItem> = emptyList(),
  val external: Boolean = false,
)

enum class TreeItemKind {
  GROUP,
  SKILL,
  PLATFORM_PACK,
  ADD_ON,
  AGENT_ADDON,
  CONFIG,
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
  val externalSourcePath: String? = null,
  val description: String? = null,
  val supportedAgents: List<String> = emptyList(),
  val consumers: List<String> = emptyList(),
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
  SAVE,
  SCAFFOLD,
  FIRST_RUN_SETUP,

  /**
   * SKILL-46: Holds the busy slot while the tree-context-menu "Delete..." preview/execute
   * triplet is in flight. F-401: must be released in both `dismissConfirmDeletion` AND the
   * stale-token branches of `finishRemoval*` so an interrupted delete never wedges the UI.
   */
  DELETE,

  /**
   * SKILL-46 AC8: holds the slot while `scripts/validate_agent_configs` runs after a successful
   * deletion. Distinct from VALIDATE (which is the in-process repo validator) so the dock can
   * surface a different running label.
   */
  VALIDATE_AGENT_CONFIGS,
}

data class CommandPaletteState(
  val open: Boolean = false,
  val query: String = "",
  val selectedResultIndex: Int = 0,
  val results: List<CommandPaletteResult> = emptyList(),
)

data class CommandPaletteResult(
  val id: String,
  val title: String = "",
  val subtitle: String = "",
  val titleRes: StringResource? = null,
  val subtitleRes: StringResource? = null,
  val marker: String,
  val kind: CommandPaletteResultKind,
  val action: CommandPaletteAction,
  val treeItemId: String? = null,
  val disabledReasonRes: StringResource? = null,
  val acceleratorLabelRes: StringResource? = null,
  val rank: Int = 0,
) {
  val enabled: Boolean
    get() = disabledReasonRes == null
}

enum class CommandPaletteResultKind {
  COMMAND,
  TREE_ITEM,
}

enum class CommandPaletteAction {
  SELECT_TREE_ITEM,
  OPEN_REPOSITORY,
  REFRESH,
  SAVE,
  INSTALL_SETUP,
  NEW_HORIZONTAL_SKILL,
  NEW_PLATFORM_PACK,
  NEW_ADD_ON,
}

data class SkillBillStatusBar(
  val targetCount: Int,
  val repoPathLabel: String,
  val readOnlyModeLabel: String,
  val policyLabel: String,
) {
  companion object {
    val empty: SkillBillStatusBar =
      SkillBillStatusBar(
        targetCount = 0,
        repoPathLabel = "no repo",
        readOnlyModeLabel = READ_ONLY_MODE_LABEL,
        policyLabel = POLICY_LABEL,
      )

    const val READ_ONLY_MODE_LABEL = "read-only"
    const val EDITABLE_MODE_LABEL = "editable"
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
  RETURN_TO_INSTALLED_WORKSPACE,
}

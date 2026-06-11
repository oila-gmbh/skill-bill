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
  val selectedPublishPaths: Set<String> = emptySet(),
  val publishPrTitle: String = "",
  val publishPrBody: String = "",
  val publishDraft: Boolean = true,
  val canPublish: Boolean = false,
  val publishDisabledReason: String? = null,
  val publishBusy: Boolean = false,
  val publishErrorMessage: String? = null,
  val publishLink: PublishLink? = null,
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
  val canReturnToInstalledWorkspace: Boolean = false,
  val dirtyEditorPrompt: DirtyEditorPrompt? = null,
  val commandPalette: CommandPaletteState = CommandPaletteState(),
  val scaffoldWizard: ScaffoldWizardState? = null,
  val firstRunSetup: FirstRunSetupState? = null,
  val postPublishReinstall: PostPublishReinstallState? = null,
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
  val baselineModified: Boolean = false,
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

  /**
   * Replays the latest completed desktop setup selection after publishing governed source changes,
   * so refreshed installed skills take effect without reopening the full setup wizard.
   */
  REINSTALL,
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
  val acceleratorLabel: String? = null,
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
  VALIDATE_SELECTED,
  RENDER,
  RENDER_ALL,
  SHOW_CHANGES,
  SHOW_HISTORY,
  SAVE,
  REFRESH_GIT_STATUS,
  INSTALL_SETUP,
  NEW_HORIZONTAL_SKILL,
  NEW_PLATFORM_PACK,
  NEW_ADD_ON,
}

object SkillBillAcceleratorLabels {
  const val SAVE = "Cmd/Ctrl S"
  const val REFRESH = "Cmd/Ctrl R"
  const val RENDER = "Cmd/Ctrl Shift R"
  const val VALIDATE = "Cmd/Ctrl Shift V"
  const val COMMIT = "Cmd/Ctrl Enter"
  const val REPO_OPEN = "Enter"
  const val COMMAND_PALETTE = "Cmd/Ctrl K"
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
) {
  val isSkillContent: Boolean
    get() {
      val normalized = path.trim().replace('\\', '/')
      return !isGenerated && isUserVisibleManagedSource(normalized)
    }

  val isSkillBillManagedSource: Boolean
    get() = !isGenerated && isSkillBillManagedSource(path.trim().replace('\\', '/'))
}

private fun isSkillBillManagedSource(path: String): Boolean = isUserVisibleManagedSource(path) ||
  isPlatformPackManifestSource(path) ||
  isSkillClassManifestSource(path) ||
  path == "README.md"

private fun isUserVisibleManagedSource(path: String): Boolean =
  isAuthoredContentSource(path) || isGovernedAddonSource(path)

private fun isAuthoredContentSource(path: String): Boolean =
  path.endsWith("/content.md") && (path.startsWith("skills/") || path.startsWith("platform-packs/"))

private fun isGovernedAddonSource(path: String): Boolean =
  path.startsWith("platform-packs/") && path.contains("/addons/") && path.endsWith(".md")

private fun isPlatformPackManifestSource(path: String): Boolean =
  path.startsWith("platform-packs/") && path.endsWith("/platform.yaml")

private fun isSkillClassManifestSource(path: String): Boolean =
  path.startsWith("orchestration/skill-classes/") && path.endsWith(".yaml")

enum class GovernedChangeConcept(val label: String) {
  SKILLS("Skills"),
  AGENTS_NATIVE_AGENTS("Agents / native agents"),
  ADD_ONS("Add-ons"),
  PLATFORM_PACKS("Platform packs"),
  ORCHESTRATION("Orchestration"),
  RUNTIME_SUPPORT("Runtime support"),
  DOCS("Docs"),
  TESTS("Tests"),
  UNKNOWN_OTHER("Unknown / other"),
  GENERATED_READ_ONLY("Generated / read-only output"),
}

data class GovernedChangedFile(
  val file: ChangedFile,
  val selectedByDefault: Boolean,
  val selectionLocked: Boolean,
  val readOnlyLabel: String? = null,
) {
  val path: String get() = file.path
}

data class GovernedChangeGroup(
  val concept: GovernedChangeConcept,
  val files: List<GovernedChangedFile>,
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
  val governedGroups: List<GovernedChangeGroup> = classifyGovernedChanges(files),
) {
  val skillContentFiles: List<ChangedFile> get() = files.filter(ChangedFile::isSkillContent)

  val skillBillManagedSourceFiles: List<ChangedFile> get() = files.filter(ChangedFile::isSkillBillManagedSource)

  val hiddenManagedSourceFiles: List<ChangedFile> get() = skillBillManagedSourceFiles.filterNot(
    ChangedFile::isSkillContent,
  )

  val nonSkillContentFiles: List<ChangedFile> get() = files.filterNot(ChangedFile::isSkillBillManagedSource)

  val skillContentGovernedGroups: List<GovernedChangeGroup>
    get() = classifyGovernedChanges(skillContentFiles)

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

enum class PublishLinkKind {
  EXISTING_PR,
  DRAFT_PR,
  COMPARE_URL,
}

data class PublishLink(
  val kind: PublishLinkKind,
  val url: String,
  val message: String? = null,
)

data class PrPublishingRequest(
  val session: RepoSession?,
  val pushTarget: GitPushTarget?,
  val compareUrl: String?,
  val title: String,
  val body: String,
  val draft: Boolean = true,
)

sealed interface PrPublishingResult {
  data class ExistingPullRequest(val url: String) : PrPublishingResult

  data class CreatedDraftPullRequest(val url: String) : PrPublishingResult

  data class CompareUrlFallback(
    val url: String,
    val reason: String,
  ) : PrPublishingResult

  data class Failed(
    val type: PrPublishingErrorType,
    val message: String,
  ) : PrPublishingResult
}

enum class PrPublishingErrorType {
  AUTH,
  NETWORK,
  REMOTE,
  PROVIDER,
}

data class GitPushTarget(
  val remoteName: String,
  val branchName: String,
  val expectedCurrentBranch: String = branchName,
  val displayName: String = "$remoteName/$branchName",
  val isLikelyCanonical: Boolean = false,
  val canonicalWarning: String? = null,
  val branchOwner: String? = null,
)

data class GitPublishingStatus(
  val pushTarget: GitPushTarget? = null,
  val aheadBehind: GitAheadBehind? = null,
  val compareUrl: String? = null,
  val errorMessage: String? = null,
) {
  val hasUnpushedCommits: Boolean get() = aheadBehind?.ahead?.let { it > 0 } == true

  companion object {
    val empty: GitPublishingStatus = GitPublishingStatus()
  }
}

fun classifyGovernedChanges(files: List<ChangedFile>): List<GovernedChangeGroup> = files
  .map { file ->
    val concept = governedConceptFor(file)
    concept to GovernedChangedFile(
      file = file,
      selectedByDefault = !file.isGenerated,
      selectionLocked = file.isGenerated,
      readOnlyLabel = "generated/read-only".takeIf { file.isGenerated },
    )
  }
  .groupBy(keySelector = { it.first }, valueTransform = { it.second })
  .map { (concept, groupedFiles) -> GovernedChangeGroup(concept = concept, files = groupedFiles) }
  .sortedBy { group -> GovernedChangeConcept.values().indexOf(group.concept) }

private fun governedConceptFor(file: ChangedFile): GovernedChangeConcept {
  val path = file.path.trim().replace('\\', '/')
  return when {
    file.isGenerated -> GovernedChangeConcept.GENERATED_READ_ONLY
    path.contains("/src/") && path.contains("Test") || path.startsWith("tests/") || path.contains("/tests/") ->
      GovernedChangeConcept.TESTS
    path.contains("/native-agents/") || path.startsWith("native-agents/") ||
      path.contains("/agents.yaml") || path.endsWith("/agents.yaml") ->
      GovernedChangeConcept.AGENTS_NATIVE_AGENTS
    path.startsWith("platform-packs/") && path.contains("/addons/") -> GovernedChangeConcept.ADD_ONS
    path.startsWith("platform-packs/") -> GovernedChangeConcept.PLATFORM_PACKS
    path.startsWith("skills/") -> GovernedChangeConcept.SKILLS
    path.startsWith("orchestration/") -> GovernedChangeConcept.ORCHESTRATION
    path.startsWith("runtime-kotlin/") || path.startsWith("install") || path.startsWith("scripts/") ->
      GovernedChangeConcept.RUNTIME_SUPPORT
    path.startsWith("docs/") || path == "README.md" || path == "AGENTS.md" || path == "CLAUDE.md" ->
      GovernedChangeConcept.DOCS
    else -> GovernedChangeConcept.UNKNOWN_OTHER
  }
}

package skillbill.desktop.core.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import skillbill.desktop.core.datastore.DesktopPreferenceStore
import skillbill.desktop.core.domain.model.ChangedFile
import skillbill.desktop.core.domain.model.ChangesSnapshot
import skillbill.desktop.core.domain.model.CommitEntry
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.GitOperationResult
import skillbill.desktop.core.domain.model.GitPublishingStatus
import skillbill.desktop.core.domain.model.GitPushTarget
import skillbill.desktop.core.domain.model.RenderSummary
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.SourceControlStatus
import skillbill.desktop.core.domain.model.ValidationSummary
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.RecentRepoRepository
import skillbill.desktop.core.domain.service.RenderGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.desktop.core.domain.service.ValidationGateway

class FakeRepoSessionService : RepoSessionService {
  override fun open(repoPath: String): RepoSession = RepoSession(
    repoPath = repoPath,
    isRecognizedSkillBillRepo = true,
    loadStatus = RepoLoadStatus(
      state = RepoLoadState.LOADED,
      message = "Loaded",
      skillCount = 1,
    ),
  )
}

class FakeSkillTreeService(private val items: List<SkillBillTreeItem>) : SkillTreeService {
  override fun treeFor(session: RepoSession?): List<SkillBillTreeItem> = items
}

class FakeAuthoringGateway : AuthoringGateway {
  override fun describeSelection(treeItemId: String): EditorPlaceholder =
    EditorPlaceholder(title = treeItemId, detail = "Selected $treeItemId")
}

class FakeGitGateway(
  initialSnapshot: ChangesSnapshot = ChangesSnapshot.empty,
  var scriptedCommits: List<CommitEntry> = emptyList(),
  var scriptedDiff: String = "",
  var scriptedStatus: SourceControlStatus = SourceControlStatus(branchLabel = "main", summary = ""),
  var throwOnSnapshot: Throwable? = null,
  var throwOnDiff: Throwable? = null,
  var throwOnCommits: Throwable? = null,
  var throwOnStage: Throwable? = null,
  var throwOnUnstage: Throwable? = null,
  var throwOnCommit: Throwable? = null,
  var throwOnPush: Throwable? = null,
  var throwOnPublishingStatus: Throwable? = null,
  var scriptedCommitResult: GitOperationResult = GitOperationResult.success,
  var scriptedPushResult: GitOperationResult = GitOperationResult.success,
  var scriptedPublishingStatus: GitPublishingStatus = GitPublishingStatus.empty,
  // Optional error message to inject into the next ChangesSnapshot returned by snapshotFor/stage/unstage.
  var scriptedSnapshotErrorMessage: String? = null,
) : GitGateway {
  var scriptedSnapshot: ChangesSnapshot = initialSnapshot

  var statusForCallCount: Int = 0
    private set

  var snapshotForCallCount: Int = 0
    private set

  var diffForCallCount: Int = 0
    private set

  var recentCommitsCallCount: Int = 0
    private set

  var stageCallCount: Int = 0
    private set

  var unstageCallCount: Int = 0
    private set

  var publishingStatusCallCount: Int = 0
    private set

  var commitCallCount: Int = 0
    private set

  var pushCallCount: Int = 0
    private set

  var lastDiffRequestedPath: String? = null
    private set

  var lastDiffRequestedStaged: Boolean? = null
    private set

  var lastStagedPaths: List<String> = emptyList()
    private set

  var lastUnstagedPaths: List<String> = emptyList()
    private set

  var lastRecentCommitsPathFilter: String? = null
    private set

  var lastRecentCommitsLimit: Int = 0
    private set

  var lastCommitMessage: String? = null
    private set

  var lastPushTarget: GitPushTarget? = null
    private set

  @Suppress("UNUSED_PARAMETER")
  override fun statusFor(session: RepoSession?): SourceControlStatus {
    statusForCallCount += 1
    return scriptedStatus.copy(summary = scriptedStatus.summary.ifBlank { session?.repoPath.orEmpty() })
  }

  @Suppress("UNUSED_PARAMETER")
  override fun snapshotFor(session: RepoSession?): ChangesSnapshot {
    snapshotForCallCount += 1
    throwOnSnapshot?.let { throw it }
    val errorMessage = scriptedSnapshotErrorMessage
    return if (errorMessage != null) scriptedSnapshot.copy(errorMessage = errorMessage) else scriptedSnapshot
  }

  @Suppress("UNUSED_PARAMETER")
  override fun diffFor(session: RepoSession?, path: String, staged: Boolean): String {
    diffForCallCount += 1
    lastDiffRequestedPath = path
    lastDiffRequestedStaged = staged
    throwOnDiff?.let { throw it }
    return scriptedDiff
  }

  override fun recentCommits(session: RepoSession?, limit: Int, pathFilter: String?): List<CommitEntry> {
    recentCommitsCallCount += 1
    lastRecentCommitsLimit = limit
    lastRecentCommitsPathFilter = pathFilter
    throwOnCommits?.let { throw it }
    // F-T01 / AC5: when no repo session is open, the gateway returns no commits so the UI's
    // empty-state branch is exercised. Production gateway short-circuits on null too.
    if (session == null) {
      return emptyList()
    }
    val filtered =
      if (pathFilter.isNullOrBlank()) {
        scriptedCommits
      } else {
        scriptedCommits.filter { entry -> entry.changedPaths.any { it == pathFilter } }
      }
    return filtered.take(limit)
  }

  @Suppress("UNUSED_PARAMETER")
  override fun stage(session: RepoSession?, paths: List<String>): ChangesSnapshot {
    stageCallCount += 1
    lastStagedPaths = paths
    throwOnStage?.let { throw it }
    // Mutate scripted snapshot to move staged paths from UNSTAGED/UNTRACKED to STAGED.
    val mutated = scriptedSnapshot.files.map { file ->
      if (file.path in paths && file.group != skillbill.desktop.core.domain.model.ChangedFileGroup.GENERATED) {
        file.copy(group = skillbill.desktop.core.domain.model.ChangedFileGroup.STAGED)
      } else {
        file
      }
    }
    scriptedSnapshot = scriptedSnapshot.copy(files = mutated)
    return snapshotFor(session)
  }

  @Suppress("UNUSED_PARAMETER")
  override fun unstage(session: RepoSession?, paths: List<String>): ChangesSnapshot {
    unstageCallCount += 1
    lastUnstagedPaths = paths
    throwOnUnstage?.let { throw it }
    val mutated = scriptedSnapshot.files.map { file ->
      if (file.path in paths && file.group == skillbill.desktop.core.domain.model.ChangedFileGroup.STAGED) {
        file.copy(group = skillbill.desktop.core.domain.model.ChangedFileGroup.UNSTAGED)
      } else {
        file
      }
    }
    scriptedSnapshot = scriptedSnapshot.copy(files = mutated)
    return snapshotFor(session)
  }

  @Suppress("UNUSED_PARAMETER")
  override fun publishingStatus(session: RepoSession?): GitPublishingStatus {
    publishingStatusCallCount += 1
    throwOnPublishingStatus?.let { throw it }
    return scriptedPublishingStatus
  }

  @Suppress("UNUSED_PARAMETER")
  override fun commit(session: RepoSession?, message: String): GitOperationResult {
    commitCallCount += 1
    lastCommitMessage = message
    throwOnCommit?.let { throw it }
    return scriptedCommitResult
  }

  @Suppress("UNUSED_PARAMETER")
  override fun push(session: RepoSession?, target: GitPushTarget): GitOperationResult {
    pushCallCount += 1
    lastPushTarget = target
    throwOnPush?.let { throw it }
    return scriptedPushResult
  }

  // Helper retained for tests that previously read a single counter.
  @Suppress("unused")
  val callCount: Int
    get() = snapshotForCallCount + diffForCallCount + recentCommitsCallCount + stageCallCount + unstageCallCount +
      publishingStatusCallCount + commitCallCount + pushCallCount

  @Suppress("unused")
  fun replaceSnapshot(snapshot: ChangesSnapshot) {
    scriptedSnapshot = snapshot
  }

  // Used by FakeChangedFile-friendly constructors in tests.
  @Suppress("unused")
  fun setFiles(files: List<ChangedFile>) {
    scriptedSnapshot = scriptedSnapshot.copy(files = files)
  }
}

class FakeValidationGateway(
  var scriptedSummary: ValidationSummary = ValidationSummary.unavailable,
  var resolveBySourcePath: Map<String, String> = emptyMap(),
  var throwOnValidate: Throwable? = null,
) : ValidationGateway {
  var validateCallCount: Int = 0
    private set

  @Suppress("UNUSED_PARAMETER")
  override fun validate(session: RepoSession?): ValidationSummary {
    validateCallCount += 1
    throwOnValidate?.let { throw it }
    return scriptedSummary
  }

  @Suppress("UNUSED_PARAMETER")
  override fun resolveTreeItemIdForSource(session: RepoSession?, sourcePath: String): String? =
    resolveBySourcePath[sourcePath]
}

class FakeRenderGateway(
  var scriptedSummary: RenderSummary = RenderSummary.unavailable,
  var throwOnRender: Throwable? = null,
) : RenderGateway {
  var callCount: Int = 0
    private set

  var lastRequestedTreeItemId: String? = null
    private set

  @Suppress("UNUSED_PARAMETER")
  override fun render(session: RepoSession?, treeItemId: String): RenderSummary {
    callCount += 1
    lastRequestedTreeItemId = treeItemId
    throwOnRender?.let { throw it }
    return scriptedSummary
  }
}

class FakeDesktopPreferenceStore(initialRepoPath: String? = null) : DesktopPreferenceStore {
  private val recentRepoPathState = MutableStateFlow(initialRepoPath)

  override val recentRepoPath: StateFlow<String?>
    get() = recentRepoPathState

  override fun rememberRepoPath(repoPath: String) {
    recentRepoPathState.value = repoPath.trim().takeIf(String::isNotEmpty)
  }

  override fun clearRecentRepoPath() {
    recentRepoPathState.value = null
  }
}

class FakeRecentRepoRepository(initialRepoPath: String? = null) : RecentRepoRepository {
  private var repoPath: String? = initialRepoPath

  override fun recentRepoPath(): String? = repoPath

  override fun rememberRepoPath(repoPath: String) {
    this.repoPath = repoPath.trim().takeIf(String::isNotEmpty)
  }

  override fun clearRecentRepoPath() {
    repoPath = null
  }
}

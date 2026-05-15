package skillbill.desktop.core.domain.service

import skillbill.desktop.core.domain.model.AuthoredContentDocument
import skillbill.desktop.core.domain.model.AuthoringSaveResult
import skillbill.desktop.core.domain.model.ChangesSnapshot
import skillbill.desktop.core.domain.model.CommitEntry
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.GitOperationResult
import skillbill.desktop.core.domain.model.GitPublishingStatus
import skillbill.desktop.core.domain.model.GitPushTarget
import skillbill.desktop.core.domain.model.PrPublishingRequest
import skillbill.desktop.core.domain.model.PrPublishingResult
import skillbill.desktop.core.domain.model.RenderSummary
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.SourceControlStatus
import skillbill.desktop.core.domain.model.ValidationSummary

interface RepoSessionService {
  fun open(repoPath: String): RepoSession
}

interface RecentRepoRepository {
  fun recentRepoPath(): String?
  fun rememberRepoPath(repoPath: String)
  fun clearRecentRepoPath()
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

interface GitGateway {
  fun statusFor(session: RepoSession?): SourceControlStatus

  // Returns the grouped changed-files snapshot for the session, or empty when no repo is open.
  // Errors must be surfaced through ChangesSnapshot.errorMessage without throwing or mutating
  // any other application state.
  fun snapshotFor(session: RepoSession?): ChangesSnapshot

  // Returns the diff text for a single changed file. When [staged] is true, returns the staged
  // (index vs HEAD) diff; otherwise returns the working-tree (vs index) diff. Failures return
  // an empty string rather than throwing.
  fun diffFor(session: RepoSession?, path: String, staged: Boolean): String

  // Returns recent commit history capped at [limit]. When [pathFilter] is non-blank, only commits
  // that touched that path are returned. When the session is null or invalid, returns an empty list.
  fun recentCommits(session: RepoSession?, limit: Int, pathFilter: String? = null): List<CommitEntry>

  // Stages the given paths and returns the post-stage snapshot. Failures surface in the snapshot's
  // errorMessage rather than throwing.
  fun stage(session: RepoSession?, paths: List<String>): ChangesSnapshot

  // Unstages the given paths and returns the post-unstage snapshot. Failures surface in the
  // snapshot's errorMessage rather than throwing.
  fun unstage(session: RepoSession?, paths: List<String>): ChangesSnapshot

  // Returns the current publish target, ahead/behind counts, and compare URL when the remote
  // topology supports one. Failures surface in GitPublishingStatus.errorMessage.
  fun publishingStatus(session: RepoSession?): GitPublishingStatus

  // Commits currently staged changes with [message]. Callers are responsible for validation gating.
  // Failures surface in the result without refreshing or replacing local repo state.
  fun commit(session: RepoSession?, message: String, paths: List<String> = emptyList()): GitOperationResult

  // Pushes to [target]. Callers are responsible for canonical-remote confirmation.
  // Failures surface in the result without refreshing or replacing local repo state.
  fun push(session: RepoSession?, target: GitPushTarget): GitOperationResult
}

interface PrPublishingGateway {
  fun publish(request: PrPublishingRequest): PrPublishingResult
}

interface ValidationGateway {
  fun validate(session: RepoSession?): ValidationSummary

  fun validateSelected(session: RepoSession?, treeItemId: String): ValidationSummary = validate(session)

  fun resolveTreeItemIdForSource(session: RepoSession?, sourcePath: String): String?
}

interface RenderGateway {
  fun render(session: RepoSession?, treeItemId: String): RenderSummary
}

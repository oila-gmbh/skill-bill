package skillbill.desktop.core.domain.service

import skillbill.desktop.core.domain.model.ChangesSnapshot
import skillbill.desktop.core.domain.model.CommitEntry
import skillbill.desktop.core.domain.model.EditorPlaceholder
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
}

interface AuthoringGateway {
  fun describeSelection(treeItemId: String): EditorPlaceholder
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
}

interface ValidationGateway {
  fun validate(session: RepoSession?): ValidationSummary

  fun resolveTreeItemIdForSource(session: RepoSession?, sourcePath: String): String?
}

interface RenderGateway {
  fun render(session: RepoSession?, treeItemId: String): RenderSummary
}

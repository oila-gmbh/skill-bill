package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.AuthoringSaveResult
import skillbill.desktop.core.domain.model.DesktopSkillRemovalRequest
import skillbill.desktop.core.domain.model.FirstRunApplyResult
import skillbill.desktop.core.domain.model.FirstRunDiscoveryResult
import skillbill.desktop.core.domain.model.FirstRunPlanResult
import skillbill.desktop.core.domain.model.FirstRunSetupRequest
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.ScaffoldCatalogSnapshot
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.ScaffoldPayload
import skillbill.desktop.core.domain.model.SkillBillTreeItem

data class RepoLoadRequest(
  val token: Long,
  val repoPath: String,
  val preserveSelection: Boolean,
  val previousRepoPath: String?,
  val previousSelection: String?,
  val previousExpandedNodeIds: Set<String>,
)

data class RepoLoadResult(
  val request: RepoLoadRequest,
  val session: RepoSession,
  val treeItems: List<SkillBillTreeItem>,
)

data class StartupRequest(
  val token: Long,
)

data class StartupResult(
  val request: StartupRequest,
  val installedWorkspaceRoot: String?,
  val recentRepoPath: String,
  val repoLoadResult: RepoLoadResult?,
)

data class EditorSaveRequest(
  val token: Long,
  val session: RepoSession?,
  val treeItemId: String?,
  val body: String,
)

data class EditorSaveResult(
  val request: EditorSaveRequest,
  val result: AuthoringSaveResult,
)

enum class ScaffoldRunMode {
  DRY_RUN,
  EXECUTE,
}

data class ScaffoldRunRequest(
  val token: Long,
  val payload: ScaffoldPayload,
  val mode: ScaffoldRunMode,
)

data class ScaffoldCatalogRequest(
  val kind: ScaffoldKind,
  val session: RepoSession?,
)

data class ScaffoldCatalogResponse(
  val kind: ScaffoldKind,
  val snapshot: ScaffoldCatalogSnapshot,
)

data class FirstRunDiscoveryRequest(
  val token: Long,
)

data class FirstRunDiscoveryResponse(
  val request: FirstRunDiscoveryRequest,
  val result: FirstRunDiscoveryResult,
)

data class FirstRunApplyRequest(
  val token: Long,
  val setupRequest: FirstRunSetupRequest,
)

data class FirstRunApplyResponse(
  val request: FirstRunApplyRequest,
  val planResult: FirstRunPlanResult,
  val applyResult: FirstRunApplyResult?,
)

data class SkillRemovalRunRequest(
  val token: Long,
  val payload: DesktopSkillRemovalRequest,
)

package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.domain.model.ChangesSnapshot
import skillbill.desktop.core.domain.model.CommitEntry
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RenderSummary
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.SourceControlStatus
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.model.ValidationSummary
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.RenderGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.desktop.core.domain.service.ValidationGateway

@Inject
class PlaceholderRepoSessionService : RepoSessionService {
  override fun open(repoPath: String): RepoSession = RepoSession(
    repoPath = repoPath,
    isRecognizedSkillBillRepo = false,
  )
}

@Inject
class PlaceholderSkillTreeService : SkillTreeService {
  @Suppress("UNUSED_PARAMETER")
  override fun treeFor(session: RepoSession?): List<SkillBillTreeItem> = listOf(
    SkillBillTreeItem(
      id = "skills",
      label = "Skills",
      kind = TreeItemKind.GROUP,
      children = listOf(
        SkillBillTreeItem(id = "s-invoice", label = "invoice-extractor", kind = TreeItemKind.PLACEHOLDER),
        SkillBillTreeItem(id = "s-meeting", label = "meeting-summarizer", kind = TreeItemKind.PLACEHOLDER),
        SkillBillTreeItem(id = "s-router", label = "intent-router", kind = TreeItemKind.PLACEHOLDER),
        SkillBillTreeItem(id = "s-csv", label = "csv-normalizer", kind = TreeItemKind.PLACEHOLDER),
        SkillBillTreeItem(id = "s-pii", label = "pii-redactor", kind = TreeItemKind.PLACEHOLDER),
        SkillBillTreeItem(id = "s-trans", label = "transcript-cleaner", kind = TreeItemKind.PLACEHOLDER),
      ),
    ),
    SkillBillTreeItem(
      id = "packs",
      label = "Platform Packs",
      kind = TreeItemKind.GROUP,
      children = listOf(
        SkillBillTreeItem(id = "p-zen", label = "zendesk-pack", kind = TreeItemKind.PLACEHOLDER),
        SkillBillTreeItem(id = "p-sf", label = "salesforce-pack", kind = TreeItemKind.PLACEHOLDER),
        SkillBillTreeItem(id = "p-slack", label = "slack-pack", kind = TreeItemKind.PLACEHOLDER),
      ),
    ),
    SkillBillTreeItem(
      id = "addons",
      label = "Add-ons",
      kind = TreeItemKind.GROUP,
      children = listOf(
        SkillBillTreeItem(id = "a-trace", label = "tracing-otel", kind = TreeItemKind.PLACEHOLDER),
        SkillBillTreeItem(id = "a-eval", label = "eval-harness", kind = TreeItemKind.PLACEHOLDER),
      ),
    ),
    SkillBillTreeItem(
      id = "agents",
      label = "Native Agents",
      kind = TreeItemKind.GROUP,
      children = listOf(
        SkillBillTreeItem(id = "n-triage", label = "support-triage", kind = TreeItemKind.PLACEHOLDER),
        SkillBillTreeItem(id = "n-onboard", label = "onboarding-bot", kind = TreeItemKind.PLACEHOLDER),
      ),
    ),
  )
}

@Inject
class PlaceholderAuthoringGateway : AuthoringGateway {
  override fun describeSelection(treeItemId: String): EditorPlaceholder = EditorPlaceholder(
    title = treeItemId,
    detail = "Selection `$treeItemId` is tracked in memory. Authoring is not enabled yet.",
  )
}

@Inject
class PlaceholderValidationGateway : ValidationGateway {
  @Suppress("UNUSED_PARAMETER")
  override fun validate(session: RepoSession?): ValidationSummary = ValidationSummary.unavailable

  @Suppress("UNUSED_PARAMETER")
  override fun resolveTreeItemIdForSource(session: RepoSession?, sourcePath: String): String? = null
}

@Inject
class PlaceholderRenderGateway : RenderGateway {
  @Suppress("UNUSED_PARAMETER")
  override fun render(session: RepoSession?, treeItemId: String): RenderSummary = RenderSummary.unavailable
}

@Inject
class PlaceholderGitGateway : GitGateway {
  override fun statusFor(session: RepoSession?): SourceControlStatus = if (session == null) {
    SourceControlStatus.empty
  } else {
    SourceControlStatus(
      branchLabel = "Repository not validated",
      summary = "Placeholder session for ${session.repoPath}. No repository files are read or written.",
    )
  }

  @Suppress("UNUSED_PARAMETER")
  override fun snapshotFor(session: RepoSession?): ChangesSnapshot = ChangesSnapshot.empty

  @Suppress("UNUSED_PARAMETER")
  override fun diffFor(session: RepoSession?, path: String, staged: Boolean): String = ""

  @Suppress("UNUSED_PARAMETER")
  override fun recentCommits(session: RepoSession?, limit: Int, pathFilter: String?): List<CommitEntry> = emptyList()

  @Suppress("UNUSED_PARAMETER")
  override fun stage(session: RepoSession?, paths: List<String>): ChangesSnapshot = ChangesSnapshot.empty

  @Suppress("UNUSED_PARAMETER")
  override fun unstage(session: RepoSession?, paths: List<String>): ChangesSnapshot = ChangesSnapshot.empty
}

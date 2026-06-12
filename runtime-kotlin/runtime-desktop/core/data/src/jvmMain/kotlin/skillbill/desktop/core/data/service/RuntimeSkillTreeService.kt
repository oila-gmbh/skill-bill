package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.service.SkillTreeService
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(UserScope::class)
class RuntimeSkillTreeService(
  private val store: RepoBrowserStore,
) : SkillTreeService {
  override fun treeFor(session: RepoSession?): List<SkillBillTreeItem> =
    if (session?.isRecognizedSkillBillRepo == true && store.snapshot.repoRoot?.toString() == session.repoPath) {
      store.snapshot.treeItems
    } else {
      emptyList()
    }

  override fun resolveGeneratedArtifactTreeItemId(session: RepoSession?, artifactPath: String): String? {
    if (session?.isRecognizedSkillBillRepo != true) {
      return null
    }
    val capturedSnapshot = store.snapshot
    if (capturedSnapshot.repoRoot?.toString() != session.repoPath) {
      return null
    }
    val root = capturedSnapshot.repoRoot
    val normalized = normalizeSourcePath(root, artifactPath)
    if (normalized.isBlank()) {
      return null
    }
    return capturedSnapshot.selections.entries
      .firstOrNull { (_, detail) ->
        detail.repoToken == capturedSnapshot.repoToken &&
          detail.kind == "generated artifact" &&
          detail.authoredPath?.replace('\\', '/') == normalized
      }
      ?.key
  }
}

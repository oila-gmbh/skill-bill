package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.data.di.DesktopRuntimeApplicationServices
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.service.InstalledWorkspaceBaselineService
import skillbill.ports.scaffold.source.model.ScaffoldSaveExactContentResult
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Files
import java.nio.file.Path

@Inject
@SingleIn(UserScope::class)
class RepoBrowserStore(
  private val runtimeServices: DesktopRuntimeApplicationServices =
    DesktopRuntimeApplicationServices.forCurrentUserHome(),
) {
  private val scaffoldService get() = runtimeServices.scaffoldService
  private val installedWorkspaceBaselineService: InstalledWorkspaceBaselineService by lazy {
    JvmInstalledWorkspaceBaselineService(JvmInstalledWorkspaceLocator(), runtimeServices)
  }

  internal var snapshot: RepoBrowserSnapshot = RepoBrowserSnapshot.empty
  var authoringSaver: (Path, String, String) -> ScaffoldSaveExactContentResult = { root, skillName, body ->
    scaffoldService.saveExactContent(root, skillName, body)
  }
  var sourceFileSaver: (Path, String) -> Unit = { sourceFile, body ->
    Files.writeString(sourceFile, body)
  }
  var baselineModifiedResolver: (Path) -> Set<String> = { root ->
    installedWorkspaceBaselineService.modifiedSkillRelativePaths(root.toString())
  }

  internal fun selectionFor(session: RepoSession?, treeItemId: String?): SelectionDetail? {
    if (session?.isRecognizedSkillBillRepo != true || treeItemId == null) {
      return null
    }
    val capturedSnapshot = snapshot
    if (capturedSnapshot.repoRoot?.toString() != session.repoPath) {
      return null
    }
    return capturedSnapshot.selections[treeItemId]?.takeIf { it.repoToken == capturedSnapshot.repoToken }
  }
}

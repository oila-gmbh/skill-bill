package skillbill.desktop.core.testing.workspace

import skillbill.desktop.core.domain.service.InstalledWorkspaceBaselineService
import java.nio.file.Path

/**
 * SKILL-77 Subtask 4: test double returning a caller-supplied modified set per workspace
 * root, so ViewModel/tree tests can assert installed-only baseline decoration without a
 * real installed workspace or baseline manifest on disk.
 */
class FakeInstalledWorkspaceBaselineService(
  var modifiedByRoot: (Path) -> Set<String> = { emptySet() },
) : InstalledWorkspaceBaselineService {
  override fun modifiedSkillRelativePaths(workspaceRoot: Path): Set<String> = modifiedByRoot(workspaceRoot)
}

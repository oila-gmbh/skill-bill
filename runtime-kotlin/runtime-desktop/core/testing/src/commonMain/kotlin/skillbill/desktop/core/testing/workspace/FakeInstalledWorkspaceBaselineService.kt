package skillbill.desktop.core.testing.workspace

import skillbill.desktop.core.domain.service.InstalledWorkspaceBaselineService

/**
 * SKILL-77 Subtask 4: test double returning a caller-supplied modified set per workspace
 * root, so ViewModel/tree tests can assert installed-only baseline decoration without a
 * real installed workspace or baseline manifest on disk.
 */
class FakeInstalledWorkspaceBaselineService(
  var modifiedByRoot: (String) -> Set<String> = { emptySet() },
) : InstalledWorkspaceBaselineService {
  override fun modifiedSkillRelativePaths(workspaceRoot: String): Set<String> = modifiedByRoot(workspaceRoot)
}

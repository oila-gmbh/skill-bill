package skillbill.desktop.core.domain.service

/**
 * SKILL-77 Subtask 4: reports which skills under an open workspace are locally modified
 * relative to the SKILL-76 baseline manifest. Returns an empty set for clone sessions
 * (any root that is not the installed workspace) and when the baseline manifest is absent,
 * so baseline indicators surface for installed-workspace sessions only.
 */
interface InstalledWorkspaceBaselineService {
  /**
   * Skill-relative paths (e.g. `skills/bill-alpha`) whose live content differs from the
   * baseline. Empty when [workspaceRoot] is not the installed workspace or no baseline
   * manifest exists.
   */
  fun modifiedSkillRelativePaths(workspaceRoot: String): Set<String>
}

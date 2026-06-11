package skillbill.ports.install.baseline

import skillbill.ports.install.baseline.model.InstalledWorkspaceBaselineStatusRequest
import skillbill.ports.install.baseline.model.InstalledWorkspaceBaselineStatusResult

/**
 * SKILL-77 Subtask 4: read-only status port reporting which installed-workspace skills
 * are locally modified relative to the SKILL-76 baseline manifest. Reuses the same
 * per-skill content hash the install reconcile policy computes, so a skill whose live
 * content hash diverges from its baseline entry is reported as modified. The port has no
 * write surface — the desktop is a consumer, never a second writer of baselines.
 */
interface InstalledWorkspaceBaselineStatusPort {
  fun modifiedSkillRelativePaths(
    request: InstalledWorkspaceBaselineStatusRequest,
  ): InstalledWorkspaceBaselineStatusResult
}

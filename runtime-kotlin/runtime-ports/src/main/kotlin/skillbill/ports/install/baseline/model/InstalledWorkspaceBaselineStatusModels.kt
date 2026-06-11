package skillbill.ports.install.baseline.model

import java.nio.file.Path

/**
 * SKILL-77 Subtask 4: request/result models for the installed-workspace baseline status
 * port. Mirrors the typed one-request/one-result convention every install capability port
 * follows.
 */
data class InstalledWorkspaceBaselineStatusRequest(
  val installRoot: Path,
  val installHome: Path,
)

data class InstalledWorkspaceBaselineStatusResult(
  val modifiedSkillRelativePaths: Set<String>,
)

package skillbill.desktop.core.domain.service

import skillbill.desktop.core.domain.model.ProvisionResult

/**
 * Provisions a git repository for the installed workspace root (`~/.skill-bill`).
 *
 * Implementations check whether the workspace is already inside a self-rooted git repository and,
 * if not, initialise one with an appropriate `.gitignore` and an initial commit containing only
 * `skills/` and `platform-packs/`.
 *
 * Implementations must be safe to call on every app open: a workspace that is already provisioned
 * must return [ProvisionResult.AlreadyProvisioned] without performing any writes.
 *
 * @see ProvisionResult
 */
interface InstalledWorkspaceGitProvisioner {
  /**
   * Provision (or skip) the git repository at the installed workspace root.
   *
   * @param workspaceRoot Absolute path to the installed workspace root (e.g. `~/.skill-bill`).
   * @return The outcome of the provisioning attempt.
   */
  fun provision(workspaceRoot: String): ProvisionResult
}

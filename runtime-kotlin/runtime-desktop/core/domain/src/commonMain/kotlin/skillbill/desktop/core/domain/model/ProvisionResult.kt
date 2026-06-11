package skillbill.desktop.core.domain.model

/**
 * Result of provisioning a git repository for the installed workspace.
 *
 * - [Provisioned]: the workspace was not a git repository and was successfully provisioned with a
 *   `.gitignore` and an initial commit containing `skills/` and `platform-packs/`.
 * - [AlreadyProvisioned]: the workspace was already a self-rooted git repository; nothing changed.
 * - [GitUnavailable]: the `git` binary could not be found or executed; the workspace is opened in
 *   a degraded (non-git) mode. The [errorMessage] is surfaced to the user.
 * - [Failed]: provisioning was attempted but failed for a reason other than a missing git binary.
 *   The [errorMessage] is surfaced to the user.
 */
sealed class ProvisionResult {
  /** The workspace was provisioned with a new git repository, `.gitignore`, and initial commit. */
  data object Provisioned : ProvisionResult()

  /** The workspace already had a self-rooted git repository; no action was taken. */
  data object AlreadyProvisioned : ProvisionResult()

  /**
   * The `git` binary was not found or could not be executed. The session opens in degraded mode.
   *
   * @property errorMessage Human-readable explanation for the changes surface.
   */
  data class GitUnavailable(val errorMessage: String) : ProvisionResult()

  /**
   * Provisioning was attempted but failed.
   *
   * @property errorMessage Human-readable explanation for the changes surface.
   */
  data class Failed(val errorMessage: String) : ProvisionResult()
}

package skillbill.domain.skillremove.model

/**
 * Sealed outcome of either [SkillRemove.previewRemoval] or [SkillRemove.executeRemoval]. Mirrors
 * the `ScaffoldRunResult` pattern so the desktop gateway can map across the coroutine boundary
 * without throwing — see the planning notes for SKILL-46 (typed discriminator from input
 * `dryRun`, never substring sniffing on a `notes` field).
 *
 * Preview-shaped calls always return [Preview]; execute-shaped calls return [Success] on the
 * happy path or [Failed] on any runtime exception.
 */
sealed class SkillRemovalResult {
  /** Dry-run / preview succeeded; [preview] is the dossier the dialog must render verbatim. */
  data class Preview(val preview: SkillRemovalPreview) : SkillRemovalResult()

  /**
   * Execute succeeded. [removedPaths]/[editedManifests]/[unlinkedSymlinks] echo what landed; the
   * gateway/UI consumes them for the success summary banner. [preview] is the same dossier we
   * showed during confirmation so we can render it inside the post-success state.
   */
  data class Success(
    val preview: SkillRemovalPreview,
    val removedPaths: List<String>,
    val editedManifests: List<String>,
    val unlinkedSymlinks: List<String>,
    /**
     * F-002-RELIABILITY-README: README edits that did NOT land but were not fatal (e.g. landmark
     * missing, count already zero). Surfaced to the desktop dialog / CLI under a "README warnings"
     * section.
     */
    val readmeWarnings: List<ReadmeCatalogWarning> = emptyList(),
  ) : SkillRemovalResult()

  /**
   * Execute failed.
   *
   * - [exceptionName] / [exceptionMessage] mirror the underlying exception's simple name + message
   *   so the dialog can display the verbatim runtime failure (AC2 transparency mandate).
   * - [rollbackComplete] is `true` only when the executor can guarantee the repo is in its
   *   pre-removal state. When `false` the desktop ViewModel locks both Preview and Delete via
   *   `partialMutationLocked` until the user acknowledges the failure (F-102/F-408-plat).
   */
  data class Failed(
    val exceptionName: String,
    val exceptionMessage: String,
    val rollbackComplete: Boolean,
  ) : SkillRemovalResult()
}

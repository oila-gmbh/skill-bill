package skillbill.domain.skillremove.model

/**
 * Records what actually landed on disk after [skillbill.domain.skillremove.SkillRemoveFileSystem.applyCascade].
 *
 * F-002-RELIABILITY-README: [readmeWarnings] surfaces non-fatal README edits that did not land
 * (for example: catalog row landmark not found, section count already 0). These are reported
 * up the stack so the dialog/CLI can render a "README warnings" section without failing the
 * whole cascade.
 */
data class AppliedCascade(
  val removedPaths: List<String>,
  val editedManifests: List<String>,
  val unlinkedSymlinks: List<String>,
  val readmeWarnings: List<ReadmeCatalogWarning> = emptyList(),
)

/**
 * F-002-RELIABILITY-README: structured warning for a single README edit that did not land.
 *
 * - [readmePath] is the repo-root-relative path to the README file the edit targeted.
 * - [kind] mirrors the [ReadmeCatalogEditKind] that produced the warning.
 * - [reason] is the human-readable explanation from the underlying scaffold helper.
 */
data class ReadmeCatalogWarning(
  val readmePath: String,
  val kind: ReadmeCatalogEditKind,
  val reason: String,
)

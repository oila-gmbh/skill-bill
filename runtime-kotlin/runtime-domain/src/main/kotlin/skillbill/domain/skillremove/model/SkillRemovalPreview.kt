package skillbill.domain.skillremove.model

/**
 * Snapshot of every concrete change [SkillRemove.previewRemoval] is committing to. The desktop
 * confirmation dialog (AC2) renders each list verbatim so the user can audit the entire cascade
 * before enabling the Delete button.
 *
 * All paths are repo-root-relative POSIX strings so the dialog can display them stably across
 * platforms; absolute resolution is the executor's job.
 */
data class SkillRemovalPreview(
  /** Repo-relative paths of files and directories that will be deleted on disk. */
  val filesystemPaths: List<String>,
  /** Manifest entries the executor will rewrite (one per logical edit). */
  val manifestEdits: List<ManifestEdit>,
  /** Symlinks the executor will unlink across installed agent providers. */
  val agentSymlinkUnlinks: List<AgentSymlinkUnlink>,
  /** Edits to root `README.md` (catalog row + skill section count). */
  val readmeCatalogEdits: List<ReadmeCatalogEdit>,
  /** Repo-relative path of the skill source root, or empty for non-skill scopes. */
  val skillDirRoot: String,
  /**
   * Names of every cascaded skill that will be removed alongside the target horizontal skill —
   * primary name first, followed by every `bill-<platform>-<name>` override and every
   * `bill-<platform>-<name>-<area>` specialist discovered on disk.
   *
   * Empty for [SkillRemovalTarget.AddOn] and may contain only the platform's auto-generated
   * skills for [SkillRemovalTarget.PlatformPack].
   */
  val cascadedSkillNames: List<String> = emptyList(),
)

/** A single manifest-edit operation the executor will apply during `executeRemoval`. */
data class ManifestEdit(
  val manifestPath: String,
  val editKind: ManifestEditKind,
  val detail: String,
)

enum class ManifestEditKind {
  REMOVE_CODE_REVIEW_AREA,
  REMOVE_DECLARED_QUALITY_CHECK_FILE,
  REMOVE_DECLARED_FILES_AREA_ENTRY,
  REMOVE_AREA_METADATA_ENTRY,

  /**
   * Drop the `declared_files.baseline:` line entirely. Emitted when removing a horizontal skill
   * whose `bill-<platform>-<slug>` baseline directory is itself being deleted, so the manifest
   * does not keep dangling pointers at non-existent content files.
   */
  REMOVE_DECLARED_FILES_BASELINE,

  /**
   * Drop a single top-level key from the `pointers:` block. The `detail` field carries the
   * exact key path (e.g. `code-review/bill-kmp-code-review-ui`) so the executor knows which
   * mapping entry to strip.
   */
  REMOVE_POINTERS_BLOCK_KEY,

  /**
   * Remove every platform-pack `pointers:` entry and `addon_usage` reference for a governed
   * add-on pointer filename. The `detail` field carries the pointer filename, e.g.
   * `android-compose-review.md`.
   */
  REMOVE_ADDON_REFERENCES,

  /**
   * Remove one pointer slug from an orchestration skill-class manifest. The `detail` field
   * carries the slug without `.md`, e.g. `android-compose-implementation`.
   */
  REMOVE_SKILL_CLASS_POINTER,
}

/** A single agent-symlink unlink the executor will apply. */
data class AgentSymlinkUnlink(
  val provider: AgentSymlinkProvider,
  val path: String,
)

enum class AgentSymlinkProvider {
  CLAUDE,
  CODEX,
  OPENCODE,
  JUNIE,
  ZCODE,
}

/** A single edit to the root `README.md` catalog. */
data class ReadmeCatalogEdit(
  val readmePath: String,
  val kind: ReadmeCatalogEditKind,
  val detail: String,
)

enum class ReadmeCatalogEditKind {
  REMOVE_CATALOG_ROW,
  DECREMENT_SECTION_COUNT,
}

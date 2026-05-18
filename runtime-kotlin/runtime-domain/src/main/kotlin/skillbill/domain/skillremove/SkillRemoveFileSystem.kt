package skillbill.domain.skillremove

import skillbill.domain.skillremove.model.AgentSymlinkUnlink
import skillbill.domain.skillremove.model.AppliedCascade
import skillbill.domain.skillremove.model.ManifestEdit
import skillbill.domain.skillremove.model.ReadmeCatalogEdit
import skillbill.domain.skillremove.model.SkillRemovalPreview
import skillbill.domain.skillremove.model.SkillRemovalRequest

/**
 * Port interface that abstracts every filesystem and install-side primitive the
 * [SkillRemove] domain service needs. The runtime-domain module keeps zero direct dependency on
 * `java.nio` or the runtime-core install primitives — the JVM implementation lives in
 * `runtime-core` (`SkillRemoveJvmFileSystem`) and is bound via DI.
 *
 * Every method that returns a [List] must return a stable, deterministic order so two consecutive
 * calls to [SkillRemove.previewRemoval] yield identical dossiers.
 */
interface SkillRemoveFileSystem {
  /** Repo-relative paths of every concrete file/directory that exists in the cascade. */
  fun resolveCascadeFilesystemPaths(request: SkillRemovalRequest, cascadedSkillNames: List<String>): List<String>

  /**
   * Discover horizontal-skill cascades. For a horizontal skill `bill-foo`, this walks
   * `platform-packs/` under `request.repoRootAbsolutePath` to find every `bill-<platform>-foo`
   * override (and `bill-<platform>-foo-<area>` specialists). Returns the list ordered as
   * primary-first.
   */
  fun discoverCascadedSkillNames(request: SkillRemovalRequest): List<String>

  /** Whether the target exists in the repository. Used by callers that want to short-circuit. */
  fun targetExists(request: SkillRemovalRequest): Boolean

  /** Manifest edits required for the cascade. */
  fun planManifestEdits(request: SkillRemovalRequest, cascadedSkillNames: List<String>): List<ManifestEdit>

  /** Agent-symlink unlinks across Claude/Codex/Opencode/Junie for every removed skill. */
  fun planAgentSymlinkUnlinks(request: SkillRemovalRequest, cascadedSkillNames: List<String>): List<AgentSymlinkUnlink>

  /** README catalog edits required for horizontal-skill scope. */
  fun planReadmeCatalogEdits(request: SkillRemovalRequest): List<ReadmeCatalogEdit>

  /**
   * Apply the cascade. Returns the actual paths/manifests/symlinks the executor mutated, which
   * may be a subset of the preview when files were already missing on disk.
   *
   * Implementations MUST attempt rollback on partial-mutation failure. If rollback succeeds the
   * implementation should re-throw the original exception; if rollback fails the implementation
   * must wrap the failure in a [SkillBillRollbackException].
   */
  fun applyCascade(request: SkillRemovalRequest, preview: SkillRemovalPreview): AppliedCascade
}

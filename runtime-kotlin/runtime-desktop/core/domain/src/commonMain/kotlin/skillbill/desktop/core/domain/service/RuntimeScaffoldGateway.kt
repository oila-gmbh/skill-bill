package skillbill.desktop.core.domain.service

import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.ScaffoldCatalogSnapshot
import skillbill.desktop.core.domain.model.ScaffoldPayload
import skillbill.desktop.core.domain.model.ScaffoldRunResult

/**
 * Desktop-facing seam over the runtime scaffolder. Mirrors the sibling-gateway pattern of
 * `RuntimeGitGateway` and `RenderGateway`: a thin, suspend-friendly interface whose
 * implementations adapt the synchronous runtime entry point (`skillbill.scaffold.scaffold`) into
 * sealed result variants the view model can consume without throwing across coroutine boundaries.
 */
interface RuntimeScaffoldGateway {
  /**
   * Project the scaffold catalog (approved areas, families, presets, piloted packs) for the
   * given [session]. When no repo is open or platform-packs cannot be discovered, returns
   * [ScaffoldCatalogSnapshot.empty]. Static lists (areas/families/presets) are always populated.
   *
   * F-002/F-404: declared `suspend` because the JVM implementation walks the `platform-packs/`
   * filesystem to discover piloted packs. Callers must hop to an I/O-suitable dispatcher
   * (`Dispatchers.Default`) before invoking this; the view model receives the snapshot and is
   * still allowed to mutate state on the main dispatcher.
   */
  suspend fun catalogSnapshot(session: RepoSession?): ScaffoldCatalogSnapshot

  /**
   * Run the scaffolder in dry-run mode. The returned result is either:
   * - [ScaffoldRunResult.Preview] when the runtime completed planning, or
   * - [ScaffoldRunResult.Failed] when the runtime rejected the payload before any mutation.
   *
   * Dry-run NEVER returns [ScaffoldRunResult.Success] because no filesystem changes are applied.
   */
  suspend fun dryRun(payload: ScaffoldPayload): ScaffoldRunResult

  /**
   * Run the scaffolder in execute mode. The returned result is either:
   * - [ScaffoldRunResult.Success] when the scaffold finished and validated, or
   * - [ScaffoldRunResult.Failed] when any runtime exception was thrown. The `rollbackComplete`
   *   flag distinguishes the runtime's normal transactional rollback from a partial-mutation
   *   leftover (`ScaffoldRollbackError`).
   */
  suspend fun execute(payload: ScaffoldPayload): ScaffoldRunResult
}

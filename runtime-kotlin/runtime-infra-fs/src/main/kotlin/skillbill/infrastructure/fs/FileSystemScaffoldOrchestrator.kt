package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.scaffold.ScaffoldAdapterSeams
import skillbill.scaffold.model.ScaffoldResult
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import skillbill.scaffold.scaffoldWithAdapters
import skillbill.scaffold.toRawScaffoldPayload

/**
 * SKILL-52.1 subtask 3 (F-001): DI-bound entrypoint for the filesystem scaffold pipeline.
 *
 * Replaces the file-static singletons that previously lived at the top of
 * `skillbill.scaffold.ScaffoldService.kt` (`private val scaffoldRepoValidation = ...` and
 * `private val scaffoldSourceLoader = ...`). Holding these as `file-static private val`s
 * coexisted with the DI-bound instances injected into application services, so two parallel
 * adapter instances were live at runtime. Routing the orchestrator through DI guarantees a
 * single adapter instance per graph.
 *
 * The orchestrator threads its kotlin-inject-bound adapters into the `ScaffoldAdapterSeams`
 * holder so the orchestrator file (`ScaffoldService.kt`) does not need to import the
 * concrete adapter class names directly.
 */
@Inject
class FileSystemScaffoldOrchestrator(
  private val repoValidation: FileSystemScaffoldRepoValidation,
  private val sourceLoader: FileSystemScaffoldSourceLoader,
) {
  fun scaffold(payload: Map<String, Any?>, dryRun: Boolean): ScaffoldResult =
    scaffoldWithAdapters(payload, dryRun, adapterSeams())

  /**
   * SKILL-52.2 subtask 2: typed entry point. Re-materialises the typed request to the legacy
   * raw-map payload shape and delegates to the existing scaffolder pipeline. This interim
   * bridge preserves byte-equivalent outputs while the application/port surface is migrated
   * to the typed API; Phase 5 reshapes the orchestrator internals to consume the typed
   * request directly and deletes this re-materialisation.
   */
  fun scaffold(request: ScaffoldCommandRequest, dryRun: Boolean): ScaffoldResult =
    scaffold(request.toRawScaffoldPayload(), dryRun)

  private fun adapterSeams(): ScaffoldAdapterSeams = ScaffoldAdapterSeams(
    validateScaffold = { plan, repoRoot -> repoValidation.validateScaffold(plan, repoRoot) },
    optionalBaselineLayers = { payload, repoRoot, newPlatform ->
      repoValidation.optionalBaselineLayers(payload, repoRoot, newPlatform)
    },
    resolveAddonConsumerSkillDirs = { payload, packRoot, pack ->
      sourceLoader.resolveAddonConsumerSkillDirs(payload, packRoot, pack)
    },
  )
}

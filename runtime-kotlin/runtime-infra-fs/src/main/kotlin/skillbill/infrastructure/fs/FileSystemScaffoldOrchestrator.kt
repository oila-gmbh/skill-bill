package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.scaffold.ScaffoldAdapterSeams
import skillbill.scaffold.model.ScaffoldResult
import skillbill.scaffold.scaffoldWithAdapters

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

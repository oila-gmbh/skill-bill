package skillbill.scaffold.runtime

import skillbill.infrastructure.fs.FileSystemScaffoldRepoValidation
import skillbill.infrastructure.fs.FileSystemScaffoldSourceLoader
import skillbill.scaffold.model.ScaffoldResult
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import skillbill.scaffold.payload.toRawScaffoldPayload

/**
 * SKILL-52.1 subtask 3 (F-001): standalone wrapper that preserves the legacy top-level
 * `scaffold(payload, dryRun)` call shape used by the in-tree scaffold rollback / parity
 * tests in `runtime-infra-fs/src/test/kotlin/skillbill/scaffold`.
 *
 * Production code paths MUST route through the DI-bound
 * `skillbill.infrastructure.fs.FileSystemScaffoldOrchestrator` so the two carved IO
 * adapters reuse the kotlin-inject singletons instead of allocating parallel instances.
 * This wrapper lives in a separate file (NOT `ScaffoldService.kt`) so the orchestrator
 * file stays free of concrete adapter-class references per F-001's structural constraint.
 *
 * The concrete adapter classes are instantiated freshly on each call here — that is
 * fine for test fixtures (each test has its own temporary repo root) but would
 * regress the F-001 fix if reused by production paths.
 */
fun scaffold(payload: Map<String, Any?>, dryRun: Boolean = false): ScaffoldResult {
  val repoValidation = FileSystemScaffoldRepoValidation()
  val sourceLoader = FileSystemScaffoldSourceLoader()
  val seams = ScaffoldAdapterSeams(
    validateScaffold = { plan, repoRoot -> repoValidation.validateScaffold(plan, repoRoot) },
    optionalBaselineLayers = { p, r, np -> repoValidation.optionalBaselineLayers(p, r, np) },
    resolveAddonConsumerSkillDirs = { p, pr, pk -> sourceLoader.resolveAddonConsumerSkillDirs(p, pr, pk) },
  )
  return scaffoldWithAdapters(payload, dryRun, seams)
}

/**
 * SKILL-52.2 subtask 2: typed standalone scaffolder used by in-tree parity tests. Re-materialises
 * the typed [request] into the legacy raw payload (interim bridge) and delegates to the existing
 * top-level [scaffold]. Phase 5 of SKILL-52.2 subtask 2 replaces this with a typed orchestrator
 * entrypoint and deletes the raw-map overload above.
 */
fun scaffold(request: ScaffoldCommandRequest, dryRun: Boolean = false): ScaffoldResult =
  scaffold(request.toRawScaffoldPayload(), dryRun)

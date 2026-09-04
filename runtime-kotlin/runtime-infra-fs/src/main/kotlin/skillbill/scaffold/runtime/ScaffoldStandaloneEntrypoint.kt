package skillbill.scaffold.runtime

import skillbill.scaffold.adapters.FileSystemScaffoldRepoValidation
import skillbill.scaffold.adapters.FileSystemScaffoldSourceLoader
import skillbill.scaffold.model.ScaffoldResult
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import skillbill.scaffold.payload.toRawScaffoldPayload
import java.nio.file.Path

fun scaffold(payload: Map<String, Any?>, dryRun: Boolean = false): ScaffoldResult {
  val repoValidation = FileSystemScaffoldRepoValidation()
  val sourceLoader = FileSystemScaffoldSourceLoader()
  val seams = ScaffoldAdapterSeams(
    validateScaffold = { plan, repoRoot -> repoValidation.validateScaffold(plan, repoRoot) },
    optionalBaselineLayers = { p, r, np -> repoValidation.optionalBaselineLayers(p, r, np) },
    resolveAddonConsumerSkillDirs = { p, pr, pk -> sourceLoader.resolveAddonConsumerSkillDirs(p, pr, pk) },
    performInstall = { _, _, _ -> emptyList<Path>() to emptyList() },
    rollbackInstallTargets = { _, _ -> },
  )
  return scaffoldWithAdapters(payload, dryRun, seams)
}

fun scaffold(request: ScaffoldCommandRequest, dryRun: Boolean = false): ScaffoldResult =
  scaffold(request.toRawScaffoldPayload(), dryRun)

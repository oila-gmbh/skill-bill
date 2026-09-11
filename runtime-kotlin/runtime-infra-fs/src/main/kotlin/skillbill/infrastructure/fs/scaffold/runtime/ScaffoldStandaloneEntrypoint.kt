package skillbill.infrastructure.fs.scaffold.runtime

import skillbill.infrastructure.fs.scaffold.adapters.FileSystemScaffoldRepoValidation
import skillbill.infrastructure.fs.scaffold.adapters.FileSystemScaffoldSourceLoader
import skillbill.infrastructure.fs.scaffold.payload.toRawScaffoldPayload
import skillbill.scaffold.model.ScaffoldResult
import skillbill.scaffold.model.command.ScaffoldCommandRequest
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

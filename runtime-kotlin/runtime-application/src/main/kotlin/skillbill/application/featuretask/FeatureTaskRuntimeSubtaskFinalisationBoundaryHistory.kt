package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch

internal fun isBoundaryHistoryPath(
  path: String,
  declaredPaths: Collection<String> = emptyList(),
  declaredRoots: Collection<String> = emptyList(),
): Boolean {
  val normalized = normalizeRepoPath(path)
  val declared = declaredPaths.map(::normalizeRepoPath).toSet()
  if (normalized !in declared) return false
  if (normalized == "agent/history.md" || normalized == "agent/decisions.md") return true
  if (!normalized.endsWith("/agent/history.md") && !normalized.endsWith("/agent/decisions.md")) return false
  val root = normalized.substringBeforeLast("/agent/", missingDelimiterValue = "")
  return root.isNotBlank() && root in declaredRoots.map(::normalizeRepoPath)
}

internal fun configuredBoundaryHistoryRoots(paths: Collection<String>): List<String> = paths
  .map(::normalizeRepoPath)
  .filter { it.endsWith("/agent/history.md") || it.endsWith("/agent/decisions.md") }
  .mapNotNull { it.substringBeforeLast("/agent/", missingDelimiterValue = "").takeIf(String::isNotBlank) }
  .distinct()
  .sorted()

internal fun FeatureTaskRuntimeResolvedBranch.boundaryHistoryProjection(): DeclaredBoundaryHistoryProjection =
  DeclaredBoundaryHistoryProjection(boundaryHistoryPaths, boundaryHistoryRoots)

internal fun declaredBoundaryHistoryProjection(
  phaseRecord: FeatureTaskRuntimePhaseRecord?,
  authoritativeRoots: Collection<String> = emptyList(),
): DeclaredBoundaryHistoryProjection {
  if (phaseRecord?.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY) {
    return DeclaredBoundaryHistoryProjection(emptyList(), emptyList())
  }
  val outputs = (phaseRecord.fileManifestIntroduced + phaseRecord.fileManifestAfter)
    .map(::normalizeRepoPath)
    .filter { isBoundaryHistoryOutputPath(it, authoritativeRoots) }
    .distinct()
    .sorted()
  return DeclaredBoundaryHistoryProjection(
    paths = outputs,
    roots = authoritativeRoots.map(::normalizeRepoPath).filter(String::isNotBlank).distinct().sorted(),
  )
}

private fun isBoundaryHistoryOutputPath(path: String, authoritativeRoots: Collection<String>): Boolean =
  isBoundaryHistoryPath(
    path = path,
    declaredPaths = listOf(path),
    declaredRoots = authoritativeRoots,
  )

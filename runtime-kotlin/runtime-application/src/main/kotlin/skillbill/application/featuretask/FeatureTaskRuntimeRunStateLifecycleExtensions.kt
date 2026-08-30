package skillbill.application.featuretask

internal fun FeatureTaskRuntimeRunState.hasPriorRecord(phaseId: String): Boolean = phaseId in priorRecords

internal fun FeatureTaskRuntimeRunState.resumedFromPriorProcess(phaseId: String): Boolean =
  phaseId in initialRecords && phaseId !in phasesLaunchedThisProcess

internal fun FeatureTaskRuntimeRunState.recordPhaseLaunched(phaseId: String) {
  phasesLaunchedThisProcess += phaseId
}

internal fun FeatureTaskRuntimeRunState.persistedBlockedReason(phaseId: String): String? = blockedRecords[phaseId]

internal fun FeatureTaskRuntimeRunState.hasBranchSetupBlock(phaseId: String): Boolean = phaseId in branchSetupBlockedPhases

internal fun FeatureTaskRuntimeRunState.clearBranchSetupBlock(phaseId: String) {
  branchSetupBlockedPhases.remove(phaseId)
  persistedAttemptCounts.remove(phaseId)
}

package skillbill.application.featuretask

fun FeatureTaskRuntimeRunState.hasPriorRecord(phaseId: String): Boolean = phaseId in priorRecords

fun FeatureTaskRuntimeRunState.resumedFromPriorProcess(phaseId: String): Boolean =
  phaseId in initialRecords && phaseId !in phasesLaunchedThisProcess

fun FeatureTaskRuntimeRunState.recordPhaseLaunched(phaseId: String) {
  phasesLaunchedThisProcess += phaseId
}

fun FeatureTaskRuntimeRunState.persistedBlockedReason(phaseId: String): String? = blockedRecords[phaseId]

fun FeatureTaskRuntimeRunState.hasBranchSetupBlock(phaseId: String): Boolean = phaseId in branchSetupBlockedPhases

fun FeatureTaskRuntimeRunState.clearBranchSetupBlock(phaseId: String) {
  branchSetupBlockedPhases.remove(phaseId)
  persistedAttemptCounts.remove(phaseId)
}

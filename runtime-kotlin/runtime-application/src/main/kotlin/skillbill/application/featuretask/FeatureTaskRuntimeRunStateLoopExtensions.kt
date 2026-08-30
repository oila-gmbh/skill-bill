package skillbill.application.featuretask

internal fun FeatureTaskRuntimeRunState.edgeIterationCount(loopId: String): Int = edgeIterationByLoop[loopId] ?: 0

internal fun FeatureTaskRuntimeRunState.recordEdgeIteration(loopId: String, edgeIteration: Int) {
  edgeIterationByLoop[loopId] = edgeIteration
  liveClaimedLoops += loopId
}

internal fun FeatureTaskRuntimeRunState.isLoopLiveClaimed(loopId: String): Boolean = loopId in liveClaimedLoops

internal fun FeatureTaskRuntimeRunState.discardStaleReentry(loopId: String) {
  inFlightReentries.remove(loopId)
  edgeIterationByLoop.remove(loopId)
  liveClaimedLoops.remove(loopId)
}

internal fun FeatureTaskRuntimeRunState.inFlightReentry(loopId: String): InFlightReentry? = inFlightReentries[loopId]

internal fun FeatureTaskRuntimeRunState.latestInFlightReentry(): Pair<String, InFlightReentry>? =
  inFlightReentries.maxByOrNull { (_, reentry) -> reentry.edgeSequenceNumber }?.toPair()

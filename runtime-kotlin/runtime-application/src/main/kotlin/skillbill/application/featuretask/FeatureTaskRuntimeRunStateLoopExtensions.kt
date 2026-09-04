package skillbill.application.featuretask

fun FeatureTaskRuntimeRunState.edgeIterationCount(loopId: String): Int = edgeIterationByLoop[loopId] ?: 0

fun FeatureTaskRuntimeRunState.recordEdgeIteration(loopId: String, edgeIteration: Int) {
  edgeIterationByLoop[loopId] = edgeIteration
  liveClaimedLoops += loopId
}

fun FeatureTaskRuntimeRunState.isLoopLiveClaimed(loopId: String): Boolean = loopId in liveClaimedLoops

fun FeatureTaskRuntimeRunState.discardStaleReentry(loopId: String) {
  inFlightReentries.remove(loopId)
  edgeIterationByLoop.remove(loopId)
  liveClaimedLoops.remove(loopId)
}

internal fun FeatureTaskRuntimeRunState.latestInFlightReentry(): Pair<String, InFlightReentry>? =
  inFlightReentries.maxByOrNull { (_, reentry) -> reentry.edgeSequenceNumber }?.toPair()

package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedUpstreamOutputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

/**
 * Pure, deterministic resolution of the three-layer phase handoff: always-injected
 * run-invariants, the latest iteration of each statically-declared upstream output
 * (so fix loops re-running a phase are picked up), and statically-declared derived
 * context. A running agent cannot select its own inputs; they come only from the
 * phase declaration.
 */
object FeatureTaskRuntimeHandoffContract {
  /**
   * Latest-iteration output per producing phase. On an iteration tie the last-recorded
   * entry wins, so callers appending chronologically get the most recent result.
   */
  fun selectLatestOutputsByPhase(
    recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
  ): Map<String, FeatureTaskRuntimePhaseOutput> {
    val latest = LinkedHashMap<String, FeatureTaskRuntimePhaseOutput>()
    recordedOutputs.forEach { output ->
      val existing = latest[output.phaseId]
      if (existing == null || output.iteration >= existing.iteration) {
        latest[output.phaseId] = output
      }
    }
    return latest
  }

  /**
   * Latest output for each upstream dependency declared by [declaration]. A declared
   * dependency with no recorded output is omitted; callers decide whether a missing
   * required upstream blocks the run.
   */
  fun resolveUpstreamOutputs(
    declaration: FeatureTaskRuntimePhaseDeclaration,
    recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
  ): FeatureTaskRuntimeResolvedUpstreamOutputs {
    val latestByPhase = selectLatestOutputsByPhase(recordedOutputs)
    val resolved = LinkedHashMap<String, FeatureTaskRuntimePhaseOutput>()
    declaration.consumedUpstreamPhaseIds.forEach { producingPhaseId ->
      latestByPhase[producingPhaseId]?.let { resolved[producingPhaseId] = it }
    }
    return FeatureTaskRuntimeResolvedUpstreamOutputs(resolved)
  }

  /**
   * Assembles the full three-layer handoff for one phase. [drivingVerdict] is forwarded for a
   * backward-edge re-entry and defaults to null, preserving the existing forward-launch assembly
   * byte-for-byte; [reentryGapCriteria] scopes an `audit_gap` re-entry and defaults to empty;
   * upstream resolution is unchanged so a re-entered agent never selects its own inputs.
   */
  fun assembleHandoff(
    declaration: FeatureTaskRuntimePhaseDeclaration,
    runInvariants: FeatureTaskRuntimeRunInvariants,
    recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
    drivingVerdict: FeatureTaskRuntimeVerdict? = null,
    reentryGapCriteria: List<String> = emptyList(),
  ): FeatureTaskRuntimePhaseHandoff = FeatureTaskRuntimePhaseHandoff(
    phaseId = declaration.phaseId,
    runInvariants = runInvariants,
    upstreamOutputs = resolveUpstreamOutputs(declaration, recordedOutputs),
    derivedContextKeys = declaration.derivedContextKeys,
    drivingVerdict = drivingVerdict,
    reentryGapCriteria = reentryGapCriteria,
  )
}

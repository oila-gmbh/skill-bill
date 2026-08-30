package skillbill.workflow.taskruntime

import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
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
    // Resolution walks the declared projections, so a producing phase with a recorded output but no
    // declaration is never picked up: the projection set is the only way in.
    declaration.projectionDeclarations.forEach { projection ->
      val sourceRef = projection.sourceRef
      if (sourceRef is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput) {
        latestByPhase[sourceRef.producingPhaseId]?.let { resolved[sourceRef.producingPhaseId] = it }
      }
    }
    FeatureTaskRuntimePhaseWorkflowDefinition.runtimeProjectorProducerPhaseIds(declaration.phaseId).forEach { phaseId ->
      latestByPhase[phaseId]?.let { resolved[phaseId] = it }
    }
    return FeatureTaskRuntimeResolvedUpstreamOutputs(resolved)
  }

  /**
   * Assembles the full three-layer handoff for one phase. [drivingVerdict] is forwarded for a
   * backward-edge re-entry and defaults to null, preserving the existing forward-launch assembly
   * byte-for-byte; [reentryGapCriteria] scopes an `audit_gap` re-entry and defaults to empty;
   * upstream resolution is unchanged so a re-entered agent never selects its own inputs.
   */
  fun assembleHandoff(request: FeatureTaskRuntimeHandoffAssemblyRequest): FeatureTaskRuntimePhaseHandoff =
    FeatureTaskRuntimePhaseHandoff(
      phaseId = request.declaration.phaseId,
      runInvariants = request.runInvariants,
      upstreamOutputs = resolveUpstreamOutputs(request.declaration, request.recordedOutputs),
      derivedContextKeys = request.declaration.derivedContextKeys,
      projectionDeclarations = request.declaration.projectionDeclarations,
      repositoryCheckpoint = request.repositoryCheckpoint,
      expectedRepositoryCheckpoint = request.expectedRepositoryCheckpoint,
      branchIdentity = request.branchIdentity,
      baseBranch = request.baseBranch,
      validationDepth = request.validationDepth,
      qualityGateSelection = request.qualityGateSelection,
      drivingVerdict = request.drivingVerdict,
      reentryGapCriteria = request.reentryGapCriteria,
      durablyClosedCriterionRefs = request.durablyClosedCriterionRefs,
      repairLedger = request.repairLedger,
      priorGapMemory = request.priorGapMemory,
    )
}

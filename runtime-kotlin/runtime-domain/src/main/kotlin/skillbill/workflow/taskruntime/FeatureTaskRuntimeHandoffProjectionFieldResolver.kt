package skillbill.workflow.taskruntime

import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration

internal object FeatureTaskRuntimeHandoffProjectionFieldResolver {
  fun resolvedProducerIteration(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): FeatureTaskRuntimeProducerIteration = when (val source = declaration.sourceRef) {
    is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput -> {
      val output = inputs.resolvedUpstream.outputsByPhaseId[source.producingPhaseId]
      if (output == null) {
        declaration.producerIteration
      } else {
        FeatureTaskRuntimeProducerIteration(source.producingPhaseId, output.iteration)
      }
    }
    is FeatureTaskRuntimeHandoffSourceRef.RunInvariantField -> declaration.producerIteration
    FeatureTaskRuntimeHandoffSourceRef.DerivedCeremonyScaling -> declaration.producerIteration
    FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence -> declaration.producerIteration
    FeatureTaskRuntimeHandoffSourceRef.RepairLedger -> declaration.producerIteration
    FeatureTaskRuntimeHandoffSourceRef.PriorGapMemory -> declaration.producerIteration
    is FeatureTaskRuntimeHandoffSourceRef.AddonContentRef -> declaration.producerIteration
  }

  fun resolveFields(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): List<FeatureTaskRuntimeHandoffProjectionField>? {
    val fields = fieldsFor(inputs, declaration)
    if (fields == null && declaration.required) {
      rejectFeatureTaskRuntimeHandoffProjection(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.MISSING_REQUIRED_SOURCE,
        "declared source '${declaration.sourceRef.wireValue}' has no recorded value.",
      )
    }
    return fields
  }

  private fun fieldsFor(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): List<FeatureTaskRuntimeHandoffProjectionField>? = when (val sourceRef = declaration.sourceRef) {
    is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput ->
      upstreamPhaseOutputFields(inputs, declaration, sourceRef)
    is FeatureTaskRuntimeHandoffSourceRef.RunInvariantField ->
      runInvariantProjectionFields(inputs.runInvariants, sourceRef.invariantField)
    FeatureTaskRuntimeHandoffSourceRef.DerivedCeremonyScaling ->
      derivedCeremonyScalingFields(inputs)
    FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence ->
      inputs.sharedReviewEvidence?.toProjectionFields()
    FeatureTaskRuntimeHandoffSourceRef.RepairLedger -> repairLedgerProjectionFields(inputs)
    FeatureTaskRuntimeHandoffSourceRef.PriorGapMemory -> inputs.priorGapMemory?.toProjectionFields()
    is FeatureTaskRuntimeHandoffSourceRef.AddonContentRef -> addonContentProjectionFields(inputs, sourceRef.slug)
  }
}

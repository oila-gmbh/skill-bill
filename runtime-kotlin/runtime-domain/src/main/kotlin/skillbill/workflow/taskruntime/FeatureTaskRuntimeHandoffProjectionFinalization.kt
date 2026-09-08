package skillbill.workflow.taskruntime

import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration

internal object FeatureTaskRuntimeHandoffProjectionFinalization {
  fun finalizationProjectionValues(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): Map<String, Any?> {
    val context = finalizationProjectionContext(inputs)
    return when (declaration.projectionContractId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST -> mapOf(
        "changed_paths" to context.changedPaths,
        "repository_checkpoint" to context.checkpoint,
      )
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BOUNDARY_CANDIDATES -> mapOf(
        "changed_paths" to context.changedPaths,
        "boundary_candidates" to context.changedPaths
          .map { it.substringBeforeLast('/', "") }
          .filter(String::isNotBlank)
          .distinct(),
      )
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_REQUEST -> mapOf(
        "path_inventory" to context.changedPaths,
        "required_inclusions" to context.changedPaths,
        "branch_identity" to context.branch,
        "gate_attestations" to listOf("audit", "review", "validate", "write_history"),
        "repository_checkpoint" to context.checkpoint,
      )
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PR_REQUEST ->
        prRequestProjection(context)
      else -> emptyMap()
    }
  }

  fun genericProducedOutputs(output: FeatureTaskRuntimePhaseOutput): Map<String, Any?> {
    val envelope = output.normalizedOutput?.envelope
      ?: JsonSupport.parseObjectOrNull(output.payload)?.let(JsonSupport::jsonElementToValue)
        ?.let(JsonSupport::anyToStringAnyMap)
      ?: return emptyMap()
    return JsonSupport.anyToStringAnyMap(envelope["produced_outputs"]).orEmpty()
  }

  private fun finalizationProjectionContext(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
  ): FinalizationProjectionContext {
    val outputs = inputs.resolvedUpstream.outputsByPhaseId
    val validation = outputs[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE]?.let {
      genericProducedOutputs(it)
    }.orEmpty()
    val checkpoint = inputs.resolvedCheckpoint?.let { mapOf("fingerprint" to it.fingerprint) }
    val changedPaths = inputs.resolvedCheckpoint?.workingTreeOwnedPaths.orEmpty()
      .distinct()
      .sorted()
    return FinalizationProjectionContext(
      validation = validation,
      checkpoint = checkpoint,
      changedPaths = changedPaths,
      branch = inputs.branchIdentity ?: "unknown",
      base = inputs.baseBranch,
      checkpointFingerprint = inputs.resolvedCheckpoint?.fingerprint,
    )
  }

  private fun prRequestProjection(context: FinalizationProjectionContext): Map<String, Any?> = mapOf(
    "changed_paths" to context.changedPaths,
    "validation_summary" to (
      context.validation["validation_result"]
        ?: context.validation["validation_summary"]
        ?: context.validation["summary"]
        ?: "completed"
      ),
    "base_branch" to context.base,
    "diff_reference" to (context.checkpointFingerprint ?: "repository-checkpoint-unavailable"),
  )

  private data class FinalizationProjectionContext(
    val validation: Map<String, Any?>,
    val checkpoint: Map<String, String>?,
    val changedPaths: List<String>,
    val branch: String,
    val base: String,
    val checkpointFingerprint: String?,
  )
}

package skillbill.workflow.taskruntime

import skillbill.contracts.JsonSupport
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
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

  // Returns null when a non-required source has no recorded value, so an optional projection is
  // omitted rather than delivered empty. A required source with no value is a hard rejection.
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

  @Suppress("CyclomaticComplexMethod")
  private fun fieldsFor(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): List<FeatureTaskRuntimeHandoffProjectionField>? = when (val sourceRef = declaration.sourceRef) {
    is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput ->
      inputs.resolvedUpstream.outputsByPhaseId[sourceRef.producingPhaseId]?.let { output ->
        FeatureTaskRuntimeHandoffProjectionValueBuilder.phaseProjectionFields(inputs, declaration, output)
          ?: listOf(
            FeatureTaskRuntimeHandoffProjectionField(
              name = FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD,
              value = declaration.inlineAlternative?.let { kind ->
                FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
                  kind = kind,
                  value = FeatureTaskRuntimeHandoffProjectionValidator.privateEvidenceReference(
                    sourceRef.producingPhaseId,
                    output.iteration,
                  ),
                )
              } ?: FeatureTaskRuntimeHandoffProjectionValue.Text(output.payload),
            ),
          )
      }
    is FeatureTaskRuntimeHandoffSourceRef.RunInvariantField ->
      runInvariantFields(inputs.runInvariants, sourceRef.invariantField)
    FeatureTaskRuntimeHandoffSourceRef.DerivedCeremonyScaling -> listOf(
      FeatureTaskRuntimeHandoffProjectionField(
        name = FeatureTaskRuntimeHandoffProjectionValidator.CEREMONY_SCALING_FIELD,
        value = FeatureTaskRuntimeHandoffProjectionValue.TextList(
          FeatureTaskRuntimePhaseWorkflowQueries
            .ceremonyScaling(inputs.runInvariants.featureSize)
            .toBriefingLines(),
        ),
      ),
    )
    FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence ->
      inputs.sharedReviewEvidence?.toProjectionFields()
    FeatureTaskRuntimeHandoffSourceRef.RepairLedger -> repairLedgerFields(inputs)
    FeatureTaskRuntimeHandoffSourceRef.PriorGapMemory -> inputs.priorGapMemory?.toProjectionFields()
    is FeatureTaskRuntimeHandoffSourceRef.AddonContentRef ->
      inputs.addonContentBySlug[sourceRef.slug]?.let { content ->
        listOf(
          FeatureTaskRuntimeHandoffProjectionField(
            name = FeatureTaskRuntimeHandoffProjectionValidator.ADDON_CONTENT_FIELD,
            value = FeatureTaskRuntimeHandoffProjectionValue.Text(content),
          ),
        )
      }
  }

  private fun repairLedgerFields(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
  ): List<FeatureTaskRuntimeHandoffProjectionField>? = inputs.repairLedger
    ?.takeUnless(FeatureTaskRuntimeRepairLedger::isEmpty)
    ?.let { ledger ->
      listOf(
        FeatureTaskRuntimeHandoffProjectionField(
          name = FeatureTaskRuntimePhaseWorkflowDefinition.REPAIR_LEDGER_PROJECTION_NAME,
          value = FeatureTaskRuntimeHandoffProjectionValue.Text(
            JsonSupport.mapToJsonString(ledger.boundedProjection().toProjectionMap()),
          ),
        ),
      )
    }

  private fun runInvariantFields(
    runInvariants: FeatureTaskRuntimeRunInvariants,
    field: FeatureTaskRuntimeRunInvariantPromptField,
  ): List<FeatureTaskRuntimeHandoffProjectionField> {
    val value = when (field) {
      FeatureTaskRuntimeRunInvariantPromptField.SPEC_REFERENCE ->
        FeatureTaskRuntimeHandoffProjectionValue.Text(runInvariants.specReference)
      FeatureTaskRuntimeRunInvariantPromptField.FEATURE_SIZE ->
        FeatureTaskRuntimeHandoffProjectionValue.Text(runInvariants.featureSize.name)
      FeatureTaskRuntimeRunInvariantPromptField.ACCEPTANCE_CRITERIA ->
        FeatureTaskRuntimeHandoffProjectionValue.TextList(runInvariants.acceptanceCriteria)
      FeatureTaskRuntimeRunInvariantPromptField.MANDATES_AND_OVERRIDES ->
        FeatureTaskRuntimeHandoffProjectionValue.TextList(runInvariants.mandatesAndOverrides)
      FeatureTaskRuntimeRunInvariantPromptField.REVIEW_POLICY ->
        FeatureTaskRuntimeHandoffProjectionValue.Text(runInvariants.codeReviewMode.name)
      FeatureTaskRuntimeRunInvariantPromptField.AGENT_ADDONS ->
        FeatureTaskRuntimeHandoffProjectionValue.TextList(
          runInvariants.agentAddonSelection.entries.map { it.slug },
        )
      FeatureTaskRuntimeRunInvariantPromptField.CEREMONY_SCALING,
      FeatureTaskRuntimeRunInvariantPromptField.FINALIZATION_CONTEXT,
      -> FeatureTaskRuntimeHandoffProjectionValue.TextList(emptyList())
    }
    return listOf(FeatureTaskRuntimeHandoffProjectionField(name = field.wireValue, value = value))
  }
}

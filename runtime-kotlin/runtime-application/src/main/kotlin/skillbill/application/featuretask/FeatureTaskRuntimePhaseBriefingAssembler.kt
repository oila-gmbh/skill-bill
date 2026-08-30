package skillbill.application.featuretask

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffProjectionValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionShape
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedReviewEvidenceReference
import skillbill.application.featuretask.model.FeatureTaskRuntimeBriefingProjectionInputs

/**
 * Per-phase allowlist of prompt-visible run invariants (AC-012). Run identity stays durable runtime
 * state on every briefing; this decides only what is *rendered* for a given phase.
 *
 * Identity, ceremony, and policy mandates reach every phase. The acceptance contract and review
 * policy reach only the phases that act on them. Finalization phases (history, commit, PR) describe
 * work already settled by audit and validate, so re-injecting the full acceptance contract there
 * only invites a finalization agent to relitigate criteria it has no authority over.
 *
 * Policy mandates are deliberately not part of that withholding. They are free-form operator
 * directives ("do not push to main", "PR targets develop") that govern exactly the irreversible
 * outward-facing phases, and this allowlist is their only delivery path.
 */
object FeatureTaskRuntimeRunInvariantPromptAllowlist {
  private val IDENTITY_CEREMONY_AND_POLICY: Set<FeatureTaskRuntimeRunInvariantPromptField> = setOf(
    FeatureTaskRuntimeRunInvariantPromptField.SPEC_REFERENCE,
    FeatureTaskRuntimeRunInvariantPromptField.FEATURE_SIZE,
    FeatureTaskRuntimeRunInvariantPromptField.CEREMONY_SCALING,
    FeatureTaskRuntimeRunInvariantPromptField.MANDATES_AND_OVERRIDES,
  )

  private val ACCEPTANCE_CONTRACT_PHASES: Set<FeatureTaskRuntimeRunInvariantPromptField> =
    IDENTITY_CEREMONY_AND_POLICY + FeatureTaskRuntimeRunInvariantPromptField.ACCEPTANCE_CRITERIA

  private val FINALIZATION: Set<FeatureTaskRuntimeRunInvariantPromptField> =
    IDENTITY_CEREMONY_AND_POLICY + FeatureTaskRuntimeRunInvariantPromptField.FINALIZATION_CONTEXT

  private val FINALIZATION_PHASE_IDS: Set<String> = setOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
  )

  fun forPhase(phaseId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> = when (phaseId) {
    in FINALIZATION_PHASE_IDS -> FINALIZATION
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> IDENTITY_CEREMONY_AND_POLICY
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR -> ACCEPTANCE_CONTRACT_PHASES
    else -> ACCEPTANCE_CONTRACT_PHASES
  }
}

/**
 * Pure, deterministic assembler of the per-phase launch briefing from a resolved handoff.
 *
 * Layer 2 is no longer a payload map. The assembler hands the phase's static projection
 * declarations to [FeatureTaskRuntimeHandoffProjectionValidator], which validates shape and
 * contract without truncating. A phase either receives a whole validated projection or the launch
 * fails loudly with a typed error naming the projection.
 *
 * Run invariants are rendered through [FeatureTaskRuntimeRunInvariantPromptAllowlist]: identity
 * fields reach every phase, while acceptance-contract, policy, and review fields reach only the
 * phases that act on them. The typed fields stay on the briefing as durable state regardless.
 */
object FeatureTaskRuntimePhaseBriefingAssembler {
  fun assemble(
    handoff: FeatureTaskRuntimePhaseHandoff,
    workflowId: String? = null,
    planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator =
      NoopFeatureTaskRuntimePlanningProjectionValidator,
    agentAddonSelection: HydratedAgentAddonSelection = HydratedAgentAddonSelection(),
    sharedReviewEvidence: FeatureTaskRuntimeSharedReviewEvidenceReference? = null,
  ): FeatureTaskRuntimePhaseLaunchBriefing {
    val boundedAddonSelection = FeatureTaskRuntimePhasePromptComposer.budgetedAddonsFor(
      handoff.phaseId,
      agentAddonSelection,
    )
    val promptDeclarations = handoff.projectionDeclarations +
      invariantDeclarations(handoff.phaseId) +
      boundedAddonSelection.entries.map { entry ->
        val slug = entry.persisted.slug
        PhaseHandoffProjectionDeclaration(
          consumerPhaseId = handoff.phaseId,
          sourceRef = FeatureTaskRuntimeHandoffSourceRef.AddonContentRef(slug),
          shape = PhaseHandoffProjectionShape(
            projectionName = "agent_addon_${slug.replace('-', '_')}",
            projectionContractId = "feature_task_runtime.agent_addon_content",
            projectionContractVersion = "0.1",
            promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
            budget = FeatureTaskRuntimeHandoffProjectionBudget.ADDON_CONTENT,
            declaredFieldNames = listOf(FeatureTaskRuntimeHandoffProjectionValidator.ADDON_CONTENT_FIELD),
          ),
        )
      }
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      briefingProjectionInputs(
        FeatureTaskRuntimeBriefingProjectionInputs(
          handoff = handoff,
          declarations = promptDeclarations,
          workflowId = workflowId,
          planningProjectionValidator = planningProjectionValidator,
          sharedReviewEvidence = sharedReviewEvidence,
          addonContentBySlug = boundedAddonSelection.entries.associate { it.persisted.slug to it.content },
        ),
      ),
    )
    val projectedHandoff = handoff.copy(projectionDeclarations = promptDeclarations)
    val briefingText = renderFeatureTaskRuntimePhaseBriefing(projectedHandoff, envelope)
    return FeatureTaskRuntimePhaseLaunchBriefing(
      phaseId = handoff.phaseId,
      specReference = handoff.runInvariants.specReference,
      featureSize = handoff.runInvariants.featureSize.name,
      acceptanceCriteria = handoff.runInvariants.acceptanceCriteria,
      mandatesAndOverrides = handoff.runInvariants.mandatesAndOverrides,
      handoffEnvelope = envelope,
      derivedContextKeys = handoff.derivedContextKeys,
      briefingText = briefingText,
      drivingVerdict = handoff.drivingVerdict?.wireValue,
      unresolvedAuditGapIds = handoff.reentryGapCriteria,
      durablyClosedCriterionRefs = handoff.durablyClosedCriterionRefs,
      priorGapMemory = handoff.priorGapMemory,
    )
  }

  private fun invariantDeclarations(phaseId: String): List<PhaseHandoffProjectionDeclaration> =
    FeatureTaskRuntimeRunInvariantPromptAllowlist.forPhase(phaseId).map { field ->
      val source = if (field == FeatureTaskRuntimeRunInvariantPromptField.CEREMONY_SCALING) {
        FeatureTaskRuntimeHandoffSourceRef.DerivedCeremonyScaling
      } else {
        FeatureTaskRuntimeHandoffSourceRef.RunInvariantField(field)
      }
      val projectedField = if (field == FeatureTaskRuntimeRunInvariantPromptField.CEREMONY_SCALING) {
        FeatureTaskRuntimeHandoffProjectionValidator.CEREMONY_SCALING_FIELD
      } else {
        field.wireValue
      }
      PhaseHandoffProjectionDeclaration(
        consumerPhaseId = phaseId,
        sourceRef = source,
        shape = PhaseHandoffProjectionShape(
          projectionName = "run_invariant_${field.wireValue}",
          projectionContractId = "feature_task_runtime.run_invariant",
          projectionContractVersion = "0.1",
          promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
          budget = FeatureTaskRuntimeHandoffProjectionBudget.PHASE_RECEIPT,
          declaredFieldNames = listOf(projectedField),
        ),
      )
    }
}

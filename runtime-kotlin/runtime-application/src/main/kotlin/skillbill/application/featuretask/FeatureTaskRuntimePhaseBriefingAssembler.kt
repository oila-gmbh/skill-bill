package skillbill.application.featuretask

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimePhaseBriefingFramingError
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.NoopFeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffProjectionValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairBatch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedReviewEvidenceReference
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import java.nio.charset.StandardCharsets

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
 * declarations to [FeatureTaskRuntimeHandoffProjectionValidator], which counts UTF-8 bytes and
 * collection items against each declaration's budget and REJECTS an oversized projection instead of
 * truncating it. There is deliberately no truncation marker: a phase either receives a whole
 * validated projection or the launch fails loudly with a typed error naming the projection.
 *
 * [FEATURE_TASK_RUNTIME_PHASE_BRIEFING_PAYLOAD_BYTE_CEILING] now bounds the non-projection framing
 * (run-invariants, directives, headers, derived-context keys). Projection bodies are bounded
 * independently by their own declared budgets, so the serialized text stays deterministically
 * bounded by the ceiling plus the declared per-projection budgets. If the framing alone exceeds the
 * ceiling the assembler loud-fails rather than silently truncate the governing contract.
 *
 * Run invariants are rendered through [FeatureTaskRuntimeRunInvariantPromptAllowlist]: identity
 * fields reach every phase, while acceptance-contract, policy, and review fields reach only the
 * phases that act on them. The typed fields stay on the briefing as durable state regardless.
 */
object FeatureTaskRuntimePhaseBriefingAssembler {
  /** Byte ceiling for the non-projection framing of [FeatureTaskRuntimePhaseLaunchBriefing.briefingText]. */
  const val FEATURE_TASK_RUNTIME_PHASE_BRIEFING_PAYLOAD_BYTE_CEILING: Int = 65_536

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
          projectionName = "agent_addon_${slug.replace('-', '_')}",
          projectionContractId = "feature_task_runtime.agent_addon_content",
          projectionContractVersion = "0.1",
          promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
          budget = FeatureTaskRuntimeHandoffProjectionBudget.ADDON_CONTENT,
          declaredFieldNames = listOf(FeatureTaskRuntimeHandoffProjectionValidator.ADDON_CONTENT_FIELD),
        )
      }
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      projectionInputs(
        handoff = handoff,
        declarations = promptDeclarations,
        workflowId = workflowId,
        planningProjectionValidator = planningProjectionValidator,
        sharedReviewEvidence = sharedReviewEvidence,
        addonContentBySlug = boundedAddonSelection.entries.associate { it.persisted.slug to it.content },
      ),
    )
    val projectedHandoff = handoff.copy(projectionDeclarations = promptDeclarations)
    val briefingText = serialize(projectedHandoff, envelope, workflowId)
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
      auditRepairItemIds = handoff.auditRepairPlan?.gaps.orEmpty()
        .flatMap { gap -> gap.repairItems.map { it.repairItemId } },
      unresolvedAuditGapIds = handoff.auditRepairState?.unresolvedGapLedger?.unresolvedGaps.orEmpty()
        .map { it.gapId },
      durablyClosedCriterionRefs = handoff.durablyClosedCriterionRefs,
    )
  }

  @Suppress("LongParameterList")
  private fun projectionInputs(
    handoff: FeatureTaskRuntimePhaseHandoff,
    declarations: List<PhaseHandoffProjectionDeclaration>,
    workflowId: String?,
    planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
    sharedReviewEvidence: FeatureTaskRuntimeSharedReviewEvidenceReference?,
    addonContentBySlug: Map<String, String>,
  ): FeatureTaskRuntimeHandoffProjectionInputs = FeatureTaskRuntimeHandoffProjectionInputs(
    consumerPhaseId = handoff.phaseId,
    declarations = declarations,
    resolvedUpstream = handoff.upstreamOutputs,
    runInvariants = handoff.runInvariants,
    resolvedCheckpoint = handoff.repositoryCheckpoint,
    sharedReviewEvidence = sharedReviewEvidence,
    expectedCheckpoint = handoff.expectedRepositoryCheckpoint,
    auditRepairPlan = handoff.auditRepairPlan,
    auditRepairState = handoff.auditRepairState,
    repairLedger = handoff.repairLedger,
    recordedFindingVerdicts = handoff.recordedFindingVerdicts,
    branchIdentity = handoff.branchIdentity,
    baseBranch = handoff.baseBranch,
    workflowId = workflowId,
    planningProjectionValidator = planningProjectionValidator,
    addonContentBySlug = addonContentBySlug,
    validationDepth = handoff.validationDepth,
  )

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
        projectionName = "run_invariant_${field.wireValue}",
        projectionContractId = "feature_task_runtime.run_invariant",
        projectionContractVersion = "0.1",
        promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
        budget = FeatureTaskRuntimeHandoffProjectionBudget.PHASE_RECEIPT,
        declaredFieldNames = listOf(projectedField),
      )
    }

  private fun serialize(
    handoff: FeatureTaskRuntimePhaseHandoff,
    envelope: FeatureTaskRuntimeHandoffEnvelope,
    workflowId: String?,
  ): String {
    // Framing = the briefing with empty projection bodies. The bodies themselves were already
    // budget-checked by the validator, so only the framing needs its own ceiling. The resolved
    // repository checkpoint renders here too and is bounded by owned-path count, not bytes, so an
    // audit whose uncommitted tree owns the whole feature can overflow the ceiling with the count cap
    // still satisfied. A bare require() would throw IllegalArgumentException past the launch handler
    // that already persisted STATUS_RUNNING and wedge the row; a typed error is caught there instead.
    val framingBytes = renderBriefing(handoff, emptyEnvelope(envelope))
      .toByteArray(StandardCharsets.UTF_8).size
    if (framingBytes > FEATURE_TASK_RUNTIME_PHASE_BRIEFING_PAYLOAD_BYTE_CEILING) {
      throw InvalidFeatureTaskRuntimePhaseBriefingFramingError(
        consumerPhaseId = handoff.phaseId,
        workflowId = workflowId,
        framingBytes = framingBytes,
        ceilingBytes = FEATURE_TASK_RUNTIME_PHASE_BRIEFING_PAYLOAD_BYTE_CEILING,
      )
    }
    return renderBriefing(handoff, envelope)
  }

  private fun emptyEnvelope(envelope: FeatureTaskRuntimeHandoffEnvelope): FeatureTaskRuntimeHandoffEnvelope =
    envelope.copy(
      projections = envelope.projections.map { projection ->
        projection.copy(
          fields = projection.fields.map { field ->
            field.copy(value = FeatureTaskRuntimeHandoffProjectionValue.Text(""))
          },
        )
      },
    )

  // Called twice: once with emptied projection bodies to measure framing, once with the real ones.
  @Suppress("LongMethod")
  private fun renderBriefing(
    handoff: FeatureTaskRuntimePhaseHandoff,
    envelope: FeatureTaskRuntimeHandoffEnvelope,
  ): String = buildString {
    appendLine("# Feature-task-runtime phase briefing")
    appendLine("phase: ${handoff.phaseId}")
    handoff.drivingVerdict?.let { verdict -> appendLine("driving_verdict: ${verdict.wireValue}") }
    appendLine()
    appendAllowlistedRunInvariants(handoff)
    appendLine()
    // Only rendered on an audit_gap re-entry; a forward launch and a review_fix re-entry both carry
    // no gap criteria, so their briefings stay byte-for-byte identical.
    if (handoff.reentryGapCriteria.isNotEmpty()) {
      appendLine("audit_gaps:")
      handoff.reentryGapCriteria.forEach { gap -> appendLine("  - $gap") }
    }
    handoff.auditRepairPlan?.let {
      appendLine("audit_remediation_execution_rules:")
      appendLine("  - Use the immutable initial preplan and plan; do not regenerate general planning.")
      appendLine(
        "  - Repair every carried gap in this single implementation invocation; never launch one pass per gap.",
      )
      appendLine("  - Treat the complete repair-item set as the exhaustive execution checklist for this invocation.")
      appendLine("  - Honor dependency order internally without deferring any runnable item to another round.")
      appendLine(
        "  - After each item, verify its repository outcome and record its terminal result before continuing.",
      )
      appendLine(
        "  - A fixed outcome requires executed_verification to show the gap's original failure_evidence " +
          "check no longer failing at its recorded artifact_ref: re-read the repaired symbols and state " +
          "the concrete repository fact that discharges that exact check. Generic inspection notes or " +
          "git diff output alone never justify fixed. Builds and tests stay deferred to validate.",
      )
      appendLine("  - Do not finish until every carried repair_item_id has exactly one terminal result.")
      appendLine("  - Emit exactly one terminal repair_item_result for every carried repair_item_id.")
      appendLine("  - already_satisfied requires distinct concrete repository and verification evidence.")
      appendLine("  - Do not defer or assign carried work to review, audit, validation, or a later phase.")
      appendLine(
        "  - If an item is genuinely unresolvable, block with both gap_id and repair_item_id " +
          "and preserve partial terminal evidence.",
      )
    }
    handoff.auditRepairState?.let { repairState ->
      val currentItemIds = handoff.auditRepairPlan?.gaps.orEmpty()
        .flatMap { gap -> gap.repairItems.map { it.repairItemId } }
      appendLine("audit_remediation_context:")
      JsonSupport.mapToJsonString(
        mapOf(
          "carried_repair_item_ids" to currentItemIds,
          "unresolved_gap_ids" to repairState.unresolvedGapLedger.unresolvedGaps.map { it.gapId },
          "prior_terminal_result_count" to repairState.repairItemResults.size,
          "audit_gap_iteration_count" to repairState.progress.auditGapIterationCount,
          "repository_fingerprint" to repairState.repositoryFingerprint,
        ) + auditRepairReentryProjection(handoff.auditRepairPlan, repairState, handoff.activeRepairBatch),
      ).lineSequence().forEach { appendLine("  $it") }
    }
    appendLine()
    appendLine("## Upstream projections (layer 2, declared and validated)")
    appendProjections(envelope)
    appendLine()
    appendRepositoryCheckpoint(handoff, envelope)
    appendLine("## Derived context (layer 3, declared)")
    if (handoff.derivedContextKeys.isEmpty()) {
      append("(none)")
    } else {
      // A derived-context key names evidence the phase must acquire itself, so the bare key leaves the
      // obligation implicit; each known key carries the instruction that makes it actionable, and an
      // unknown key still renders so a newly declared one is visible rather than silently dropped.
      // diff / current_unit_of_work name the shared_review_evidence projection only when that
      // projection was actually delivered; on the required=false omit path they fall back to
      // self-read so the agent is never told to dereference a missing reference.
      val sharedEvidenceDelivered = envelope.projections.any {
        it.projectionName == SHARED_EVIDENCE_PROJECTION
      }
      append(
        handoff.derivedContextKeys.joinToString(separator = "\n") { key ->
          derivedContextInstruction(key, sharedEvidenceDelivered)
            ?.let { instruction -> "- $key: $instruction" }
            ?: "- $key"
        },
      )
    }
  }

  /**
   * The complete ordered set of still-open repair obligations, reconstructed from durable audit-repair state
   * alone. First entry, in-process retry, and fresh-process resume all reach this from the same records, so a
   * resumed repair is delivered the same batch it would have been delivered originally — each still-open item
   * exactly once, with its dependencies, the evidence prior attempts already recorded, and the criteria a
   * repair must not regress.
   */
  private fun auditRepairReentryProjection(
    plan: FeatureTaskRuntimeAuditRepairPlan?,
    repairState: FeatureTaskRuntimeAuditRepairState,
    activeBatch: FeatureTaskRuntimeRepairBatch?,
  ): Map<String, Any?> {
    val priorResults = repairState.repairItemResults.associateBy { it.repairItemId }
    val items = plan?.gaps.orEmpty().flatMap { gap -> gap.repairItems.map { gap.gapId to it } }
    // Closure is scoped to the ACTIVE batch, not to every retained result. A recurring gap regenerates the
    // same repair_item_id, so an earlier round's result for that id is prior evidence for a re-opened
    // obligation; filtering on it delivered an empty open set while the completion gate still demanded a
    // terminal result. Only a workflow with no durable generation falls back to the retained results.
    val closed = activeBatch?.repairItemDispositions?.mapTo(linkedSetOf()) { it.repairItemId }
      ?: priorResults.keys
    return mapOf(
      "open_repair_items" to items.filterNot { (_, item) -> item.repairItemId in closed }
        .map { (gapId, item) ->
          mapOf(
            "gap_id" to gapId,
            "repair_item_id" to item.repairItemId,
            "depends_on" to item.dependsOn,
          )
        },
      "prior_repair_item_evidence" to items.mapNotNull { (_, item) -> priorResults[item.repairItemId] }
        .map { result ->
          mapOf(
            "repair_item_id" to result.repairItemId,
            "outcome" to result.outcome.name.lowercase(),
            "artifact_ref" to result.resultEvidence.artifactRef,
            "check_ref" to result.resultEvidence.checkRef,
          )
        },
      "non_regression_criterion_refs" to repairState.satisfiedCriterionRefs,
    )
  }

  private const val SHARED_EVIDENCE_PROJECTION: String =
    FeatureTaskRuntimePhaseWorkflowDefinition.SHARED_REVIEW_EVIDENCE_PROJECTION_NAME

  private const val SELF_READ_DIFF_INSTRUCTION: String =
    "read the branch diff yourself; it is not delivered in this briefing"

  private const val SHARED_EVIDENCE_DIFF_INSTRUCTION: String =
    "the branch diff is already derived for you: the '$SHARED_EVIDENCE_PROJECTION' projection above " +
      "carries its store_path, checkpoint_fingerprint, base_ref/head_ref, and per-file hunk index; " +
      "work from that reference, and dereference store_path when you need the diff bytes themselves"

  private const val SHARED_EVIDENCE_UNIT_INSTRUCTION: String =
    "the current unit of work is already derived for you: the '$SHARED_EVIDENCE_PROJECTION' projection " +
      "above carries its store_path, checkpoint_fingerprint, base_ref/head_ref, and per-file hunk index; " +
      "work from that reference, and dereference store_path when you need the diff bytes themselves"

  private const val SELF_READ_UNIT_INSTRUCTION: String =
    "read the current unit of work yourself; the shared evidence projection is not delivered in this briefing"

  private fun derivedContextInstruction(key: String, sharedEvidenceDelivered: Boolean): String? = when (key) {
    FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_DIFF ->
      if (sharedEvidenceDelivered) SHARED_EVIDENCE_DIFF_INSTRUCTION else SELF_READ_DIFF_INSTRUCTION
    "current_unit_of_work" ->
      if (sharedEvidenceDelivered) SHARED_EVIDENCE_UNIT_INSTRUCTION else SELF_READ_UNIT_INSTRUCTION
    FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_SCOPED_REPOSITORY_STATE ->
      "read the repository at the resolved checkpoint above — the diff over base_ref/head_ref plus " +
        "the listed scoped_owned_paths — and treat that actual state, not any upstream receipt claim, " +
        "as the evidence for every criterion"
    FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_PR_BRANCH_DIFF ->
      SELF_READ_DIFF_INSTRUCTION
    else -> null
  }
}

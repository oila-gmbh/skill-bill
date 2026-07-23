package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffProjectionValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.canonicalAcceptanceCriterionRef
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
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
  )

  fun forPhase(phaseId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> =
    if (phaseId in FINALIZATION_PHASE_IDS) FINALIZATION else ACCEPTANCE_CONTRACT_PHASES
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
  ): FeatureTaskRuntimePhaseLaunchBriefing {
    val envelope = FeatureTaskRuntimeHandoffProjectionValidator.validate(
      FeatureTaskRuntimeHandoffProjectionInputs(
        consumerPhaseId = handoff.phaseId,
        declarations = handoff.projectionDeclarations,
        resolvedUpstream = handoff.upstreamOutputs,
        runInvariants = handoff.runInvariants,
        resolvedCheckpoint = handoff.repositoryCheckpoint,
        expectedCheckpoint = handoff.expectedRepositoryCheckpoint,
        workflowId = workflowId,
      ),
    )
    val briefingText = serialize(handoff, envelope)
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

  private fun serialize(handoff: FeatureTaskRuntimePhaseHandoff, envelope: FeatureTaskRuntimeHandoffEnvelope): String {
    // Framing = the briefing with empty projection bodies. The bodies themselves were already
    // budget-checked by the validator, so only the framing needs its own ceiling.
    val framingBytes = renderBriefing(handoff, emptyEnvelope(envelope))
      .toByteArray(StandardCharsets.UTF_8).size
    require(framingBytes <= FEATURE_TASK_RUNTIME_PHASE_BRIEFING_PAYLOAD_BYTE_CEILING) {
      "Feature-task-runtime per-phase briefing layer-1/framing is $framingBytes bytes, exceeding the " +
        "$FEATURE_TASK_RUNTIME_PHASE_BRIEFING_PAYLOAD_BYTE_CEILING-byte ceiling before any projection body is " +
        "inlined; the governing contract (spec reference, acceptance criteria, mandates) is too large to fit a " +
        "single phase briefing and must not be silently truncated."
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
    // Only rendered on an audit_gap re-entry; a forward launch and a review_fix re-entry both carry
    // no gap criteria, so their briefings stay byte-for-byte identical.
    if (handoff.reentryGapCriteria.isNotEmpty()) {
      appendLine("audit_gaps:")
      handoff.reentryGapCriteria.forEach { gap -> appendLine("  - $gap") }
    }
    handoff.auditRepairPlan?.let { plan ->
      appendLine("audit_repair_plan:")
      JsonSupport.mapToJsonString(auditRepairPlanToWire(plan)).lineSequence().forEach { appendLine("  $it") }
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
        ),
      ).lineSequence().forEach { appendLine("  $it") }
    }
    appendLine()
    appendAllowlistedRunInvariants(handoff)
    appendLine()
    appendLine("## Upstream projections (layer 2, declared and validated)")
    appendProjections(envelope)
    appendLine()
    appendLine("## Derived context (layer 3, declared)")
    if (handoff.derivedContextKeys.isEmpty()) {
      append("(none)")
    } else {
      append(handoff.derivedContextKeys.joinToString(separator = "\n") { key -> "- $key" })
    }
  }

  // Only prompt-visible projections render; a private-evidence-only projection stays durable state.
  private fun StringBuilder.appendProjections(envelope: FeatureTaskRuntimeHandoffEnvelope) {
    val visible = envelope.promptVisibleProjections
    if (visible.isEmpty()) {
      appendLine("(none)")
      return
    }
    visible.forEach { projection ->
      val sourceRef = projection.sourceRef
      // The upstream heading stays keyed by producing phase so a consumer reads "from: plan", not a
      // projection name it has no topology for.
      if (sourceRef is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput) {
        appendLine("### from: ${sourceRef.producingPhaseId}")
      } else {
        appendLine("### ${projection.projectionName} (${sourceRef.wireValue})")
      }
      projection.fields.forEach { field -> appendProjectionField(projection, field) }
    }
  }

  private fun StringBuilder.appendProjectionField(
    projection: FeatureTaskRuntimeHandoffProjection,
    field: FeatureTaskRuntimeHandoffProjectionField,
  ) {
    val single = projection.fields.size == 1 &&
      field.name == FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD
    when (val value = field.value) {
      is FeatureTaskRuntimeHandoffProjectionValue.Text ->
        if (single) appendLine(value.text) else appendLine("${field.name}: ${value.text}")
      is FeatureTaskRuntimeHandoffProjectionValue.TextList -> {
        appendLine("${field.name}:")
        value.items.forEach { item -> appendLine("  - $item") }
      }
      is FeatureTaskRuntimeHandoffProjectionValue.CompactReference ->
        appendLine("${field.name}: ${value.kind.wireValue}=${value.value}")
    }
  }

  // Layer 1 rendering is allowlist-driven per phase; the typed fields stay on the briefing regardless.
  private fun StringBuilder.appendAllowlistedRunInvariants(handoff: FeatureTaskRuntimePhaseHandoff) {
    val invariants = handoff.runInvariants
    val allowlist = FeatureTaskRuntimeRunInvariantPromptAllowlist.forPhase(handoff.phaseId)
    appendLine("## Run invariants (layer 1, unconditional)")
    if (FeatureTaskRuntimeRunInvariantPromptField.SPEC_REFERENCE in allowlist) {
      appendLine("spec_reference: ${invariants.specReference}")
    }
    if (FeatureTaskRuntimeRunInvariantPromptField.FEATURE_SIZE in allowlist) {
      appendLine("feature_size: ${invariants.featureSize.name}")
    }
    if (FeatureTaskRuntimeRunInvariantPromptField.CEREMONY_SCALING in allowlist) {
      appendLine("ceremony_scaling:")
      FeatureTaskRuntimePhaseWorkflowDefinition.ceremonyScaling(invariants.featureSize)
        .toBriefingLines()
        .forEach { line -> appendLine("  $line") }
    }
    if (FeatureTaskRuntimeRunInvariantPromptField.ACCEPTANCE_CRITERIA in allowlist) {
      appendAcceptanceCriteria(handoff)
    }
    if (FeatureTaskRuntimeRunInvariantPromptField.MANDATES_AND_OVERRIDES in allowlist) {
      appendLine("mandates_and_overrides:")
      if (invariants.mandatesAndOverrides.isEmpty()) {
        appendLine("  (none)")
      } else {
        invariants.mandatesAndOverrides.forEach { mandate -> appendLine("  - $mandate") }
      }
    }
  }

  private fun StringBuilder.appendAcceptanceCriteria(handoff: FeatureTaskRuntimePhaseHandoff) {
    appendLine("acceptance_criteria:")
    val closedCriterionRefs = handoff.durablyClosedCriterionRefs.toSet()
    handoff.runInvariants.acceptanceCriteria.forEachIndexed { index, criterion ->
      val criterionRef = canonicalAcceptanceCriterionRef(index + 1)
      if (criterionRef !in closedCriterionRefs) appendLine("  $criterionRef. $criterion")
    }
    if (closedCriterionRefs.isNotEmpty()) {
      appendLine("durably_closed_criteria:")
      appendLine("  (each reached a satisfied verdict and is closed; do not re-verify or report a gap against it)")
      closedCriterionRefs.sorted().forEach { criterionRef -> appendLine("  - $criterionRef") }
    }
  }
}

package skillbill.workflow.taskruntime.model

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_AUDIT_GENERATION_CONTRACT_VERSION

/**
 * Explicit gap lifecycle at the generation layer. [FeatureTaskRuntimePriorGapDisposition.Status] stays the
 * two-state wire shape the audit handoff speaks; this is the durable authority, and it distinguishes a gap
 * newly opened, one re-reported under its existing identity, one verified closed, one closed by governed
 * supersession, and one carried forward with no new decision.
 */
enum class FeatureTaskRuntimeAuditGapState {
  NEW,
  RECURRING,
  RESOLVED,
  SUPERSEDED,
  STILL_OPEN,
  ;

  val terminal: Boolean get() = this == RESOLVED || this == SUPERSEDED
  val open: Boolean get() = !terminal
}

/**
 * A gap dispositioned `superseded` is closed without repository evidence that the behavior now exists, so
 * the closure has to name the authority that governs it. Without this a supersession is indistinguishable
 * from an unverified claim of completion.
 */
data class FeatureTaskRuntimeGovernanceEvidence(
  val governingDecision: String,
  val authorityRef: String,
  val rationale: String,
) {
  init {
    requireDurableText(governingDecision, "governing_decision")
    requireDurableText(rationale, "rationale")
    requireRule(
      authorityRef.length <= MAX_AUDIT_REPAIR_REF_LENGTH,
      "authority_ref allows at most $MAX_AUDIT_REPAIR_REF_LENGTH characters.",
    ) {
      "authority_ref allows at most $MAX_AUDIT_REPAIR_REF_LENGTH characters, had ${authorityRef.length}."
    }
    requireRule(
      GENERATION_ARTIFACT_REF.matches(authorityRef),
      "authority_ref must be a bounded path or symbol reference such as docs/decision.md or " +
        "src/main/Example.kt:Example.",
    ) {
      "authority_ref '$authorityRef' must be a bounded path or symbol reference such as docs/decision.md " +
        "or src/main/Example.kt:Example."
    }
  }
}

enum class FeatureTaskRuntimeRepairDisposition { FIXED, ALREADY_SATISFIED, SUPERSEDED }

data class FeatureTaskRuntimeRepairItemDisposition(
  val repairItemId: String,
  val disposition: FeatureTaskRuntimeRepairDisposition,
  val resultEvidence: FeatureTaskRuntimeEvidence,
  val governanceEvidence: FeatureTaskRuntimeGovernanceEvidence? = null,
) {
  init {
    requireGenerationRepairItemId(repairItemId)
    val expectedObservation = when (disposition) {
      FeatureTaskRuntimeRepairDisposition.FIXED -> FeatureTaskRuntimeEvidence.Observation.FIX_VERIFIED
      FeatureTaskRuntimeRepairDisposition.ALREADY_SATISFIED ->
        FeatureTaskRuntimeEvidence.Observation.ALREADY_SATISFIED_VERIFIED
      FeatureTaskRuntimeRepairDisposition.SUPERSEDED ->
        FeatureTaskRuntimeEvidence.Observation.RESOLUTION_VERIFIED
    }
    requireRule(
      resultEvidence.observation == expectedObservation,
      "result_evidence.observation must pair with the repair disposition.",
    ) {
      "Repair item '$repairItemId' disposition '${disposition.wire()}' requires result_evidence.observation " +
        "'${expectedObservation.wire()}', was '${resultEvidence.observation.wire()}'."
    }
    val supersededWithoutGovernance =
      disposition == FeatureTaskRuntimeRepairDisposition.SUPERSEDED && governanceEvidence == null
    requireRule(
      !supersededWithoutGovernance,
      "A repair item dispositioned 'superseded' requires governance_evidence.",
    ) { "Repair item '$repairItemId' is dispositioned 'superseded' without governance_evidence." }
    val governanceOnVerifiedOutcome =
      disposition != FeatureTaskRuntimeRepairDisposition.SUPERSEDED && governanceEvidence != null
    requireRule(
      !governanceOnVerifiedOutcome,
      "governance_evidence is representable only for a 'superseded' disposition.",
    ) {
      "Repair item '$repairItemId' carries governance_evidence with disposition " +
        "'${disposition.wire()}'; only 'superseded' may carry it."
    }
  }

  val terminal: Boolean get() = true
}

data class FeatureTaskRuntimeRepairBatch(
  val batchId: String,
  val repairItemIds: List<String>,
  val repairItemDispositions: List<FeatureTaskRuntimeRepairItemDisposition>,
) {
  init {
    requireRule(
      BATCH_ID.matches(batchId),
      "batch_id must be the stable per-workflow identity 'batch-<generation-ordinal>'.",
    ) { "batch_id '$batchId' must be the stable per-workflow identity 'batch-<generation-ordinal>'." }
    requireRule(
      repairItemIds.size <= MAX_AUDIT_REPAIR_ITEMS,
      "A repair batch allows at most $MAX_AUDIT_REPAIR_ITEMS repair items.",
    ) { "A repair batch allows at most $MAX_AUDIT_REPAIR_ITEMS repair items, had ${repairItemIds.size}." }
    repairItemIds.forEach(::requireGenerationRepairItemId)
    requireUnique(repairItemIds, "repair_batch.repair_item_ids")
    requireUnique(
      repairItemDispositions.map { it.repairItemId },
      "repair_batch.repair_item_dispositions.repair_item_id",
    )
    val declared = repairItemIds.toSet()
    val undeclared = repairItemDispositions.map { it.repairItemId }.filterNot(declared::contains).sorted()
    requireRule(
      undeclared.isEmpty(),
      "Every repair-item disposition must name a repair item the same batch declares.",
    ) { "Batch '$batchId' dispositions name undeclared repair items $undeclared." }
  }

  val unclosedRepairItemIds: List<String>
    get() {
      val closed = repairItemDispositions.mapTo(linkedSetOf()) { it.repairItemId }
      return repairItemIds.filterNot(closed::contains)
    }

  val closureComplete: Boolean get() = unclosedRepairItemIds.isEmpty()
}

/**
 * Read-only repository facts about what the disposed repair batch touched. Follow-up audit cannot emit a
 * satisfied verdict without one, so a repair that closed its carried gaps while breaking a neighbouring
 * boundary is still caught.
 */
data class FeatureTaskRuntimeBlastRadiusInspection(
  val inspectedPaths: List<String>,
  val newlyIntroducedGapIds: List<String>,
  val evidence: FeatureTaskRuntimeEvidence,
) {
  init {
    requireRule(
      inspectedPaths.isNotEmpty(),
      "A blast-radius inspection must name at least one inspected production path.",
    ) { "A blast-radius inspection must name at least one inspected production path, inspected_paths was empty." }
    requireCompactList(inspectedPaths, "inspected_paths", MAX_PATH_LIST_ITEMS)
    inspectedPaths.forEach { path ->
      requireRule(
        path.length <= MAX_AUDIT_REPAIR_REF_LENGTH && GENERATION_ARTIFACT_REF.matches(path),
        "inspected_paths entries must be bounded path or symbol references.",
      ) { "inspected_paths entry '$path' must be a bounded path or symbol reference." }
    }
    requireUnique(inspectedPaths, "inspected_paths")
    requireRule(
      newlyIntroducedGapIds.size <= MAX_AUDIT_REPAIR_GAPS,
      "A blast-radius inspection allows at most $MAX_AUDIT_REPAIR_GAPS newly introduced gaps.",
    ) {
      "A blast-radius inspection allows at most $MAX_AUDIT_REPAIR_GAPS newly introduced gaps, " +
        "had ${newlyIntroducedGapIds.size}."
    }
    newlyIntroducedGapIds.forEach(::requireGenerationGapId)
    requireUnique(newlyIntroducedGapIds, "newly_introduced_gap_ids")
  }
}

enum class FeatureTaskRuntimeCriterionInspectionVerdict { SATISFIED, GAP }

data class FeatureTaskRuntimeCriterionInspection(
  val acceptanceCriterionRef: String,
  val inspectionVerdict: FeatureTaskRuntimeCriterionInspectionVerdict,
) {
  init {
    requireRule(
      GENERATION_CRITERION_REF.matches(acceptanceCriterionRef),
      "acceptance_criterion_ref must use canonical format 'AC-NNN'.",
    ) { "acceptance_criterion_ref '$acceptanceCriterionRef' must use canonical format 'AC-NNN'." }
  }
}

data class FeatureTaskRuntimeGenerationGap(
  val gapId: String,
  val acceptanceCriterionRef: String,
  val acceptanceCriterionText: String,
  val state: FeatureTaskRuntimeAuditGapState,
  val recurrenceCount: Int,
  val failureEvidence: FeatureTaskRuntimeEvidence,
  val diagnosis: String,
  val affectedBoundary: String,
  val repairItemIds: List<String>,
) {
  init {
    requireGenerationGapId(gapId)
    requireRule(
      GENERATION_CRITERION_REF.matches(acceptanceCriterionRef),
      "acceptance_criterion_ref must use canonical format 'AC-NNN'.",
    ) { "acceptance_criterion_ref '$acceptanceCriterionRef' must use canonical format 'AC-NNN'." }
    requireRule(
      gapId.startsWith("${acceptanceCriterionRef.lowercase()}-gap-"),
      "gap_id must belong to its own acceptance_criterion_ref, lowercased.",
    ) { "gap_id '$gapId' must belong to acceptance criterion '$acceptanceCriterionRef'." }
    requireDurableText(acceptanceCriterionText, "acceptance_criterion_text")
    requireDurableText(diagnosis, "diagnosis")
    requireDurableText(affectedBoundary, "affected_boundary")
    requireRule(recurrenceCount >= 0, "recurrence_count must be non-negative.") {
      "recurrence_count must be non-negative, was $recurrenceCount."
    }
    val recurringWithoutRecurrence = state == FeatureTaskRuntimeAuditGapState.RECURRING && recurrenceCount < 1
    requireRule(
      !recurringWithoutRecurrence,
      "A gap in state 'recurring' must carry a recurrence_count of at least 1.",
    ) { "Gap '$gapId' is 'recurring' with recurrence_count $recurrenceCount." }
    requireRule(
      repairItemIds.size <= MAX_AUDIT_REPAIR_ITEMS,
      "A gap allows at most $MAX_AUDIT_REPAIR_ITEMS repair items.",
    ) { "Gap '$gapId' allows at most $MAX_AUDIT_REPAIR_ITEMS repair items, had ${repairItemIds.size}." }
    requireUnique(repairItemIds, "repair_item_ids")
    val foreignItems = repairItemIds.filterNot { it.startsWith("$gapId-item-") }.sorted()
    requireRule(
      foreignItems.isEmpty(),
      "Every repair_item_id must be an ordered child of its own gap_id.",
    ) { "Gap '$gapId' names repair items belonging to another gap: $foreignItems." }
    val openWithoutRepair = state.open && repairItemIds.isEmpty()
    requireRule(
      !openWithoutRepair,
      "An open gap must name at least one repair item; zero repair work is not an open gap.",
    ) { "Gap '$gapId' is open in state '${state.wire()}' with no repair items." }
  }

  val generation: Int get() = gapId.substringAfterLast("-gap-").toInt()
}

/**
 * One append-only completeness-audit generation: the sole durable authority for what an audit inspected,
 * the checkpoint it decided at, the gap identities and states it recorded, and the repair batch it
 * authorized. A later plan or checkpoint appends the next ordinal; it never rewrites this one.
 */
data class FeatureTaskRuntimeAuditGeneration(
  val generationOrdinal: Int,
  val repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint,
  val inspectedCriteria: List<FeatureTaskRuntimeCriterionInspection>,
  val satisfiedCriterionRefs: List<String>,
  val gaps: List<FeatureTaskRuntimeGenerationGap>,
  val repairBatch: FeatureTaskRuntimeRepairBatch,
  val blastRadiusInspection: FeatureTaskRuntimeBlastRadiusInspection? = null,
  val contractVersion: String = AUDIT_GENERATION_CONTRACT_VERSION,
) {
  init {
    requireRule(
      contractVersion == AUDIT_GENERATION_CONTRACT_VERSION,
      "contract_version must be '$AUDIT_GENERATION_CONTRACT_VERSION'.",
    ) { "contract_version must be '$AUDIT_GENERATION_CONTRACT_VERSION', was '$contractVersion'." }
    requireRule(generationOrdinal >= 1, "generation_ordinal must be 1-based.") {
      "generation_ordinal must be 1-based, was $generationOrdinal."
    }
    requireRule(
      authorizingOrdinal in 1..generationOrdinal,
      "repair_batch.batch_id must name this generation or an earlier one.",
    ) {
      "repair_batch.batch_id '${repairBatch.batchId}' names generation $authorizingOrdinal, which is not " +
        "this generation ($generationOrdinal) or an earlier one."
    }
    requireFullCriterionInspection()
    requireUnique(gaps.map { it.gapId }, "gap_id")
    requireClosureCompleteBatch()
  }

  /**
   * Every acceptance criterion in run scope is inspected exactly once and the verdicts partition into the
   * satisfied set and the gap set. A criterion the audit silently skipped is what let an incomplete audit
   * report a satisfied verdict.
   */
  private fun requireFullCriterionInspection() {
    requireRule(inspectedCriteria.isNotEmpty(), "inspected_criteria must cover every acceptance criterion.") {
      "inspected_criteria must cover every acceptance criterion, was empty."
    }
    requireUnique(inspectedCriteria.map { it.acceptanceCriterionRef }, "inspected_criteria.acceptance_criterion_ref")
    requireUnique(satisfiedCriterionRefs, "satisfied_criterion_refs")
    val inspected = inspectedCriteria.associate { it.acceptanceCriterionRef to it.inspectionVerdict }
    val declaredSatisfied = satisfiedCriterionRefs.toSet()
    val gapCriteria = gaps.filter { it.state.open }.mapTo(linkedSetOf()) { it.acceptanceCriterionRef }
    val misverdicted = inspected.filter { (ref, verdict) ->
      when (verdict) {
        FeatureTaskRuntimeCriterionInspectionVerdict.SATISFIED -> ref !in declaredSatisfied || ref in gapCriteria
        FeatureTaskRuntimeCriterionInspectionVerdict.GAP -> ref in declaredSatisfied || ref !in gapCriteria
      }
    }.keys.sorted()
    requireRule(
      misverdicted.isEmpty(),
      "Each inspected criterion's verdict must agree with the satisfied set and the open-gap set.",
    ) {
      "Inspected criteria whose verdict disagrees with the satisfied set or the open-gap set: $misverdicted."
    }
    val uninspected = (declaredSatisfied + gapCriteria).filterNot(inspected::containsKey).sorted()
    requireRule(uninspected.isEmpty(), "Every reported criterion must appear in inspected_criteria.") {
      "Criteria reported without an inspection record: $uninspected."
    }
  }

  /**
   * A self-authorized batch is exactly the open gaps' repair work, so no open gap can be left without a
   * repair obligation. A carried batch keeps the identity and membership it was authorized with; its items
   * must still belong to gaps this generation lists, which is what stops a disposition from closing an
   * obligation the generation does not carry.
   */
  private fun requireClosureCompleteBatch() {
    if (selfAuthorizedBatch) {
      val expected = gaps.filter { it.state.open }.flatMap { it.repairItemIds }
      requireRule(
        repairBatch.repairItemIds == expected,
        "A self-authorized repair batch must declare exactly the ordered repair items of this generation's " +
          "open gaps.",
      ) {
        "repair_batch.repair_item_ids ${repairBatch.repairItemIds} must be exactly the ordered repair items " +
          "of this generation's open gaps $expected."
      }
      return
    }
    val listed = gaps.flatMapTo(linkedSetOf()) { it.repairItemIds }
    val foreign = repairBatch.repairItemIds.filterNot(listed::contains).sorted()
    requireRule(
      foreign.isEmpty(),
      "A carried repair batch's items must all belong to a gap this generation lists.",
    ) { "Carried batch '${repairBatch.batchId}' names repair items no listed gap owns: $foreign." }
  }

  val authorizingOrdinal: Int get() = repairBatch.batchId.substringAfterLast('-').toInt()
  val selfAuthorizedBatch: Boolean get() = authorizingOrdinal == generationOrdinal
  val openGapIds: List<String> get() = gaps.filter { it.state.open }.map { it.gapId }
  val satisfiedVerdictEligible: Boolean get() = openGapIds.isEmpty() && blastRadiusInspection != null
}

/**
 * The ordered append-only generation history for one workflow. Every audit-convergence metric is derived
 * here rather than stored, so a counter cannot drift from the history it claims to summarize.
 */
data class FeatureTaskRuntimeAuditGenerationHistory(
  val generations: List<FeatureTaskRuntimeAuditGeneration>,
) {
  init {
    val ordinals = generations.map { it.generationOrdinal }
    requireRule(
      ordinals == ordinals.sorted() && ordinals.toSet().size == ordinals.size,
      "Audit generations must be strictly increasing in generation_ordinal and never reuse one.",
    ) { "Audit generations must be strictly increasing in generation_ordinal, was $ordinals." }
    requireRule(
      ordinals.isEmpty() || ordinals == (1..ordinals.size).toList(),
      "Audit generation ordinals must be a dense 1-based sequence with no gaps.",
    ) { "Audit generation ordinals must be a dense 1-based sequence, was $ordinals." }
    requireRecurrenceMonotonicity()
    requireStateTransitions()
  }

  private fun requireRecurrenceMonotonicity() {
    val highWater = mutableMapOf<String, Int>()
    generations.forEach { generation ->
      generation.gaps.forEach { gap ->
        val previous = highWater[gap.gapId]
        requireRule(
          previous == null || gap.recurrenceCount >= previous,
          "recurrence_count for one gap identity must never decrease across generations.",
        ) {
          "Gap '${gap.gapId}' recurrence_count dropped from $previous to ${gap.recurrenceCount} at " +
            "generation ${generation.generationOrdinal}."
        }
        highWater[gap.gapId] = gap.recurrenceCount
      }
    }
  }

  private fun requireStateTransitions() {
    val latest = mutableMapOf<String, FeatureTaskRuntimeAuditGapState>()
    generations.forEach { generation ->
      generation.gaps.forEach { gap ->
        requireGapStateTransition(latest[gap.gapId], gap.state, gap.gapId, generation.generationOrdinal)
        latest[gap.gapId] = gap.state
      }
    }
  }

  val latestGeneration: FeatureTaskRuntimeAuditGeneration? get() = generations.lastOrNull()

  /** Latest recorded state per gap identity, the authority for whether a gap is still open. */
  fun latestGapStates(): Map<String, FeatureTaskRuntimeAuditGapState> {
    val states = linkedMapOf<String, FeatureTaskRuntimeAuditGapState>()
    generations.forEach { generation -> generation.gaps.forEach { states[it.gapId] = it.state } }
    return states
  }

  fun recurrenceCounts(): Map<String, Int> {
    val counts = linkedMapOf<String, Int>()
    generations.forEach { generation -> generation.gaps.forEach { counts[it.gapId] = it.recurrenceCount } }
    return counts
  }

  /**
   * The active repair batch: the open repair items of the latest generation. Exactly one batch is active at
   * any time because a generation is appended, never forked.
   */
  fun activeRepairBatch(): FeatureTaskRuntimeRepairBatch? =
    latestGeneration?.repairBatch?.takeIf { !it.closureComplete }

  fun deriveProgress(auditGapIterationCount: Int): FeatureTaskRuntimeAuditRepairProgress {
    val seenGapIds = mutableSetOf<String>()
    var newGapCount = 0
    var recurringGapCount = 0
    var attempted = 0
    var resolved = 0
    generations.forEach { generation ->
      generation.gaps.forEach { gap ->
        if (seenGapIds.add(gap.gapId)) newGapCount++
        if (gap.state == FeatureTaskRuntimeAuditGapState.RECURRING) recurringGapCount++
      }
      // A carried batch repeats its authorizing generation's item list, so only the generation that
      // authorized it may count those items as attempted; dispositions are counted where they are recorded.
      if (generation.selfAuthorizedBatch) attempted += generation.repairBatch.repairItemIds.size
      resolved += generation.repairBatch.repairItemDispositions.size
    }
    return FeatureTaskRuntimeAuditRepairProgress(
      // The initial audit converged on its first pass exactly when it opened no gap at all; any later
      // generation exists because a repair obligation did.
      firstPassConvergence = generations.size == 1 && generations.single().openGapIds.isEmpty(),
      recurringGapCount = recurringGapCount,
      newGapCount = newGapCount,
      attemptedRepairItemCount = attempted,
      resolvedRepairItemCount = resolved,
      auditGapIterationCount = auditGapIterationCount,
    )
  }
}

/**
 * A gap identity moves forward only. `resolved` and `superseded` are terminal, so a later generation cannot
 * silently reopen a closed identity — a defect found again after closure is a new identity, which is what
 * keeps recurrence honest.
 */
fun requireGapStateTransition(
  previous: FeatureTaskRuntimeAuditGapState?,
  next: FeatureTaskRuntimeAuditGapState,
  gapId: String,
  generationOrdinal: Int,
) {
  val allowed = when (previous) {
    null -> setOf(FeatureTaskRuntimeAuditGapState.NEW)
    FeatureTaskRuntimeAuditGapState.NEW,
    FeatureTaskRuntimeAuditGapState.RECURRING,
    FeatureTaskRuntimeAuditGapState.STILL_OPEN,
    -> setOf(
      FeatureTaskRuntimeAuditGapState.RECURRING,
      FeatureTaskRuntimeAuditGapState.STILL_OPEN,
      FeatureTaskRuntimeAuditGapState.RESOLVED,
      FeatureTaskRuntimeAuditGapState.SUPERSEDED,
    )
    FeatureTaskRuntimeAuditGapState.RESOLVED,
    FeatureTaskRuntimeAuditGapState.SUPERSEDED,
    -> emptySet()
  }
  requireRule(
    next in allowed,
    "A gap identity's state transition must be one the gap lifecycle allows.",
  ) {
    "Gap '$gapId' cannot move from '${previous?.wire() ?: "<absent>"}' to '${next.wire()}' at generation " +
      "$generationOrdinal; allowed next states are ${allowed.map { it.wire() }.sorted()}."
  }
}

/** Wire-compatible projection of the two-state audit handoff disposition onto the generation lifecycle. */
fun FeatureTaskRuntimePriorGapDisposition.Status.toGapState(): FeatureTaskRuntimeAuditGapState = when (this) {
  FeatureTaskRuntimePriorGapDisposition.Status.RESOLVED -> FeatureTaskRuntimeAuditGapState.RESOLVED
  FeatureTaskRuntimePriorGapDisposition.Status.RECURRING -> FeatureTaskRuntimeAuditGapState.RECURRING
}

const val AUDIT_GENERATION_CONTRACT_VERSION: String = FEATURE_TASK_RUNTIME_AUDIT_GENERATION_CONTRACT_VERSION
const val FEATURE_TASK_RUNTIME_AUDIT_GENERATION_MAX_ORDINAL: Int = 10_000

private val GENERATION_GAP_ID = Regex("ac-[0-9]{3,}-gap-[1-9][0-9]*")
private val GENERATION_REPAIR_ITEM_ID = Regex("ac-[0-9]{3,}-gap-[1-9][0-9]*-item-[1-9][0-9]*")
private val GENERATION_CRITERION_REF = Regex("AC-[0-9]{3}")
private val GENERATION_ARTIFACT_REF = Regex("(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.#-]+)?")
private val BATCH_ID = Regex("batch-[1-9][0-9]*")

private fun requireGenerationGapId(gapId: String) {
  requireRule(
    GENERATION_GAP_ID.matches(gapId),
    "gap_id must be the stable criterion-generation identifier '<criterion-ref>-gap-<generation>' in " +
      "canonical lowercase, for example 'ac-005-gap-1'.",
  ) {
    "gap_id '$gapId' must be the stable criterion-generation identifier '<criterion-ref>-gap-<generation>' " +
      "in canonical lowercase, for example 'ac-005-gap-1'."
  }
}

private fun requireGenerationRepairItemId(repairItemId: String) {
  requireRule(
    GENERATION_REPAIR_ITEM_ID.matches(repairItemId),
    "repair_item_id must be the stable ordered child '<gap-id>-item-<ordinal>' in canonical lowercase.",
  ) {
    "repair_item_id '$repairItemId' must be the stable ordered child '<gap-id>-item-<ordinal>', for example " +
      "'ac-005-gap-1-item-1'."
  }
}

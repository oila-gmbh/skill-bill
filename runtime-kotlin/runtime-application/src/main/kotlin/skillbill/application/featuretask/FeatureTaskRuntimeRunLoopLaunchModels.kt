package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

internal sealed interface AttemptResult {
  data class Settled(val outcome: PhaseOutcome) : AttemptResult

  /**
   * The two consumers of a schema-invalid attempt want different text and must not share one string.
   * [operatorReason] is the payload-free sentence a blocked row, telemetry event or status surface may
   * carry; [retryReason] is the constraint text the next fix-loop prompt needs to repair the output.
   *
   * Bounding belongs to whoever composes [retryReason] — [retryRejectionReason] for validator-authored
   * text — not to this constructor. Re-bounding a composed string here would spend the validator's
   * character budget on the runtime's own fixed preamble and truncate exactly the constraint the prompt
   * exists to deliver.
   */
  data class SchemaInvalid(
    val operatorReason: String,
    val retryReason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
    override val rejectedOutput: String?,
    override val malformedOutput: Boolean,
    override val correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext?,
  ) : AttemptResult

  /**
   * A schema-VALID receipt that did not close every obligation the plan declared. It gets its own
   * variant rather than reusing [SchemaInvalid] because nothing about it is invalid: recording it as
   * a rejected output would file honest partial work as malformed serialization, and routing it
   * through the schema path would spend the structural-repair budget on a structurally fine
   * document. [malformedOutput] and [schemaInvalidRetryReason] stay false/null for it, which is what
   * keeps it out of the format-correction cap.
   */
  data class IncompleteWork(
    val operatorReason: String,
    val continuationReason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
    val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ) : AttemptResult

  /**
   * A retryable `blocked` or `failed` envelope. It is a schema-valid terminal signal that happens to
   * be retryable, so it re-enters the semantic fix loop WITHOUT being relabelled schema-invalid and
   * without consuming the malformed-output or structural-repair budget.
   */
  data class RetryableTerminal(
    val operatorReason: String,
    val retryReason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
    val failureDisposition: FeatureTaskRuntimeFailureDisposition,
  ) : AttemptResult

  /**
   * A schema-VALID verify_findings disposition that selected boundary headings and must continue
   * once the runtime delivers those entry bodies. Own variant so the handshake never spends the
   * output-gate budget the way [SchemaInvalid] does.
   */
  data class BoundaryBodyDelivery(
    val continuationReason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ) : AttemptResult

  /**
   * A well-formed repair receipt that still owes work on named carried findings — either it left
   * them out, or it reported it tried and they are still open.
   *
   * Its own variant for the same reason [IncompleteWork] is: nothing about the document is invalid,
   * so it must not spend the output-gate budget. It is not [IncompleteWork] either, because that
   * path rebuilds a plan-task continuation projection from durable implementation attempts, and
   * what this round owes is a named set of findings, not an unclosed obligation. The finding refs
   * are the budget, and [kind] selects which rule reads them.
   */
  data class FindingsOwed(
    val kind: FindingsOwedKind,
    val operatorReason: String,
    val retryReason: String,
    val refs: Set<String>,
    val detail: String?,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ) : AttemptResult

  val settledOutcome: PhaseOutcome? get() = (this as? Settled)?.outcome
  val schemaInvalidOperatorReason: String? get() = (this as? SchemaInvalid)?.operatorReason
  val schemaInvalidRetryReason: String? get() = (this as? SchemaInvalid)?.retryReason
  val fileManifest: FeatureTaskRuntimePhaseFileManifest?
    get() = when (this) {
      is Settled -> null
      is SchemaInvalid -> fileManifest
      is IncompleteWork -> fileManifest
      is RetryableTerminal -> fileManifest
      is FindingsOwed -> fileManifest
      is BoundaryBodyDelivery -> fileManifest
    }
  val rejectedOutput: String? get() = (this as? SchemaInvalid)?.rejectedOutput
  val malformedOutput: Boolean get() = (this as? SchemaInvalid)?.malformedOutput == true
  val correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext?
    get() = (this as? SchemaInvalid)?.correctiveRepairContext

  /** The operator-facing sentence for whichever non-settled variant this is. */
  val retryableOperatorReason: String?
    get() = when (this) {
      is Settled -> null
      is SchemaInvalid -> operatorReason
      is IncompleteWork -> operatorReason
      is RetryableTerminal -> operatorReason
      is FindingsOwed -> operatorReason
      is BoundaryBodyDelivery -> null
    }

  /**
   * Prompt-facing constraint text for the SCHEMA-correction fix loop; null on every other path.
   *
   * [RetryableTerminal] is deliberately absent: it is schema-valid, so feeding its reason here would
   * render it through the schema-correction directive and tell the agent its output was rejected
   * when it was not. It exposes its own [retryableTerminalRetryReason] instead.
   */
  val semanticRetryReason: String?
    get() = when (this) {
      is Settled -> null
      is SchemaInvalid -> retryReason
      is IncompleteWork -> null
      is RetryableTerminal -> null
      is FindingsOwed -> null
      is BoundaryBodyDelivery -> null
    }

  /** Prompt-facing constraint text for a retryable terminal envelope's own continuation directive. */
  val retryableTerminalRetryReason: String? get() = (this as? RetryableTerminal)?.retryReason

  /** The envelope's declared disposition, so a capped terminal retry never blocks as INVALID_OUTPUT. */
  val retryableTerminalDisposition: FeatureTaskRuntimeFailureDisposition?
    get() = (this as? RetryableTerminal)?.failureDisposition

  /** Why this round still owes findings, or null when it owes none. */
  val findingsOwedKind: FindingsOwedKind? get() = (this as? FindingsOwed)?.kind

  /** The finding references the owed-work budget counts. */
  val findingsOwedRefs: Set<String>? get() = (this as? FindingsOwed)?.refs

  /** Prompt-facing continuation text naming what is still owed. */
  val findingsOwedRetryReason: String? get() = (this as? FindingsOwed)?.retryReason

  /** The producer's own account of what still fails, carried only by an unresolved report. */
  val findingsOwedDetail: String? get() = (this as? FindingsOwed)?.detail

  val incompleteWorkContinuationReason: String? get() = (this as? IncompleteWork)?.continuationReason
  val incompleteWorkOutput: NormalizedFeatureTaskRuntimePhaseOutput?
    get() = (this as? IncompleteWork)?.normalizedOutput
  val boundaryBodyDeliveryContinuationReason: String?
    get() = (this as? BoundaryBodyDelivery)?.continuationReason

  companion object {
    fun settled(outcome: PhaseOutcome): AttemptResult = Settled(outcome)

    fun boundaryBodyDelivery(
      continuationReason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest,
    ): AttemptResult = BoundaryBodyDelivery(continuationReason, fileManifest)

    fun incompleteWork(
      operatorReason: String,
      continuationReason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest,
      normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    ): AttemptResult = IncompleteWork(operatorReason, continuationReason, fileManifest, normalizedOutput)

    fun unaccountedItems(
      phaseId: String,
      itemNoun: String,
      unaccountedRefs: List<String>,
      retryReason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest,
    ): AttemptResult = FindingsOwed(
      kind = FindingsOwedKind.OMITTED,
      operatorReason = "Phase '$phaseId' left carried $itemNoun unaccounted for in its output: " +
        unaccountedRefs.joinToString(", ") + ".",
      retryReason = retryReason,
      refs = unaccountedRefs.toSet(),
      detail = null,
      fileManifest = fileManifest,
    )

    fun unresolvedFindings(
      unresolvedRefs: Set<String>,
      detail: String,
      retryReason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest,
    ): AttemptResult = FindingsOwed(
      kind = FindingsOwedKind.UNRESOLVED,
      operatorReason = "Phase 'implement_fix' reported carried review findings still open after " +
        "its attempt: ${unresolvedRefs.joinToString(", ")}.",
      retryReason = retryReason,
      refs = unresolvedRefs,
      detail = detail,
      fileManifest = fileManifest,
    )

    fun retryableTerminal(
      operatorReason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest,
      failureDisposition: FeatureTaskRuntimeFailureDisposition,
    ): AttemptResult = RetryableTerminal(operatorReason, operatorReason, fileManifest, failureDisposition)
    fun schemaInvalid(args: SchemaInvalidArgs): AttemptResult = SchemaInvalid(
      operatorReason = args.operatorReason,
      retryReason = args.retryReason ?: args.operatorReason,
      fileManifest = args.fileManifest,
      rejectedOutput = args.rejectedOutput,
      malformedOutput = args.malformedOutput,
      correctiveRepairContext = args.correctiveRepairContext,
    )
  }
}

internal sealed interface PhaseOutcome {
  data class Completed(val output: FeatureTaskRuntimePhaseOutput) : PhaseOutcome
  data class Blocked(val reason: String) : PhaseOutcome

  // Non-terminal and resumable: the phase stopped for a condition that clears without operator
  // repair (today, a provider usage limit). Distinct from Blocked so no seam can settle the run as
  // terminally blocked on it.
  data class Paused(val reason: String) : PhaseOutcome

  // SKILL-140: the consumer rejected an upstream producer's record and quarantined it; the run
  // settles the consumer with the RECORD_REJECTED verdict so the existing transition machinery
  // re-enters this producer under a bounded regeneration cap.
  data class RegenerateProducer(val producerPhaseId: String) : PhaseOutcome

  val completedOutput: FeatureTaskRuntimePhaseOutput? get() = (this as? Completed)?.output

  val blockedReason: String? get() = (this as? Blocked)?.reason

  val pausedReason: String? get() = (this as? Paused)?.reason

  val regenerationTargetPhaseId: String? get() = (this as? RegenerateProducer)?.producerPhaseId

  companion object {
    fun completed(output: FeatureTaskRuntimePhaseOutput): PhaseOutcome = Completed(output)
    fun blocked(reason: String): PhaseOutcome = Blocked(reason)
    fun paused(reason: String): PhaseOutcome = Paused(reason)
    fun regenerateProducer(producerPhaseId: String): PhaseOutcome = RegenerateProducer(producerPhaseId)
  }
}

internal sealed interface GoalReviewRunPreparation {
  data object CarryForward : GoalReviewRunPreparation
  class Blocked(
    val reason: String,
    val failureDisposition: FeatureTaskRuntimeFailureDisposition,
  ) : GoalReviewRunPreparation
}

internal data class GoalReviewRunReady(val run: PhaseRun) : GoalReviewRunPreparation

const val READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES = 30L

const val LEGACY_PLANNING_PROJECTION_LAUNCH_SEAM_REJECTION =
  "rejected an upstream bounded planning projection at the launch seam"

const val OWNED_PATH_DELIMITER = '\u0000'

const val MAX_CHECKPOINT_OWNED_PATHS = 2000

fun parseContentIdentities(raw: String): Map<String, String> = raw
  .split(OWNED_PATH_DELIMITER)
  .filter(String::isNotBlank)
  .mapNotNull { record ->
    val identity = record.substringBefore('\t', missingDelimiterValue = "")
    val path = record.substringAfter('\t', missingDelimiterValue = "")
    if (identity.isBlank() || path.isBlank()) null else path to identity
  }
  .toMap()

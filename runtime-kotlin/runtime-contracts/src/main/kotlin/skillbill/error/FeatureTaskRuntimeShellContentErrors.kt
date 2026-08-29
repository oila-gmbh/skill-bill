package skillbill.error

enum class FeatureTaskRuntimePhaseOutputFailureKind(
  override val wireValue: String,
) : FailureWireCode {
  MALFORMED("malformed"),
  SCHEMA_INVALID("schema_invalid"),
}

data class FeatureTaskRuntimePhaseOutputStructuralRepairSource(
  val label: String,
  val offset: Int,
  val line: Int,
  val column: Int,
)

data class FeatureTaskRuntimePhaseOutputStructuralRepair(
  val originalDigest: String,
  val repairedDigest: String,
  val format: String,
  val operation: String,
  val source: FeatureTaskRuntimePhaseOutputStructuralRepairSource,
)

class InvalidFeatureTaskRuntimePhaseOutputSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
  /**
   * The same violation as [reason], restated with schema-side content only: the violated rule, the
   * expected shape, and the offending field's name or path. It never carries an instance value, a body
   * fragment, or any other span of the response that failed — [reason] is the value-bearing variant and
   * belongs only in a private diagnostic row or a local log. Null means the throwing seam had no
   * mechanically value-free restatement available; a consumer must then fall back to its own payload-free
   * rejection sentence and must never substitute [reason].
   */
  val payloadFreeReason: String? = null,
  /** Stable wire code used by the typed adapter result; old callers may omit it. */
  val failureCode: String = "schema_invalid",
  /**
   * Payload-free structural-repair correlation retained when delimiter repair accepted this capture
   * and the phase schema later rejected it. Digests, format/operation wire values, and source
   * location only — never response body text. Absence means no prior syntax repair on this capture.
   */
  val structuralRepair: FeatureTaskRuntimePhaseOutputStructuralRepair? = null,
) : ShellContentContractException(
  "Feature-task-runtime phase output '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
  cause,
) {
  val failureKind: FeatureTaskRuntimePhaseOutputFailureKind
    get() = coarseFailureKindForPhaseOutputWireCode(failureCode)

  val structuralRepairOriginalDigest: String?
    get() = structuralRepair?.originalDigest

  val structuralRepairRepairedDigest: String?
    get() = structuralRepair?.repairedDigest

  val structuralRepairFormat: String?
    get() = structuralRepair?.format

  val structuralRepairOperation: String?
    get() = structuralRepair?.operation

  val structuralRepairSourceLabel: String?
    get() = structuralRepair?.source?.label

  val structuralRepairSourceOffset: Int?
    get() = structuralRepair?.source?.offset

  val structuralRepairSourceLine: Int?
    get() = structuralRepair?.source?.line

  val structuralRepairSourceColumn: Int?
    get() = structuralRepair?.source?.column

  /**
   * True when deterministic delimiter repair previously accepted this capture and the phase schema
   * later rejected it. Syntax success must not be read as phase-schema acceptance.
   */
  val acceptedAfterStructuralRepair: Boolean
    get() = structuralRepair != null
}

/** Why a handoff projection was rejected before an agent was launched. */
enum class FeatureTaskRuntimeHandoffProjectionFailureKind(
  override val wireValue: String,
) : FailureWireCode {
  MISSING_REQUIRED_SOURCE("missing_required_source"),
  MALFORMED_FIELD("malformed_field"),
  UNSUPPORTED_CONTRACT_VERSION("unsupported_contract_version"),
  UNDECLARED_FIELD("undeclared_field"),
  DUPLICATE_PROJECTION_NAME("duplicate_projection_name"),
  BUDGET_OVERFLOW("budget_overflow"),
  INVALID_COMPACT_REFERENCE("invalid_compact_reference"),
  CHECKPOINT_POLICY_VIOLATION("checkpoint_policy_violation"),
  SCHEMA_INVALID("schema_invalid"),
}

/**
 * Surfaced when a declared handoff projection cannot be delivered. The message names the workflow
 * (when known), the consumer phase, the projection, and its contract id/version, plus a short
 * caller-supplied [reason]. Call sites pass a diagnosis, never payload or field content, so a
 * rejection is actionable without echoing the private evidence it refused to project.
 */
@Suppress("LongParameterList") // each identifier is required by the actionable-message contract
class InvalidFeatureTaskRuntimeHandoffProjectionError(
  val workflowId: String?,
  val consumerPhaseId: String,
  val projectionName: String,
  val projectionContractId: String,
  val projectionContractVersion: String,
  val failureKind: FeatureTaskRuntimeHandoffProjectionFailureKind,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task-runtime handoff projection '${projectionName.ifBlank { "<unknown>" }}' " +
    "(contract ${projectionContractId.ifBlank { "<unknown>" }}@${projectionContractVersion.ifBlank { "<unknown>" }}) " +
    "for consumer phase '${consumerPhaseId.ifBlank { "<unknown>" }}' " +
    "in workflow '${workflowId?.ifBlank { null } ?: "<unknown>"}' " +
    "was rejected [$failureKind]: $reason",
  cause,
)

/**
 * Surfaced when the non-projection framing of a phase briefing exceeds its byte ceiling before any
 * projection body is inlined. The realistic driver is the audit repository checkpoint, whose owned-path
 * inventory renders in the framing pass and is bounded by count, not bytes. A bare check would throw
 * `IllegalArgumentException` past the launch handler that already persisted STATUS_RUNNING, wedging the
 * row with no blocked reason and crash-looping on every resume; this typed error is caught at that seam
 * and the phase blocks durably instead. The message names the measured size and the ceiling only, never
 * the framing content it refused to deliver.
 */
class InvalidFeatureTaskRuntimePhaseBriefingFramingError(
  val consumerPhaseId: String,
  val workflowId: String?,
  val framingBytes: Int,
  val ceilingBytes: Int,
) : ShellContentContractException(
  "Feature-task-runtime phase '${consumerPhaseId.ifBlank { "<unknown>" }}' " +
    "in workflow '${workflowId?.ifBlank { null } ?: "<unknown>"}' " +
    "has a launch briefing whose layer-1/framing is $framingBytes bytes, over the $ceilingBytes-byte ceiling " +
    "before any projection body is inlined; the governing contract plus resolved repository checkpoint is too " +
    "large for a single phase briefing and must not be silently truncated. Narrow the run scope or commit " +
    "unrelated working-tree changes before relaunching.",
)

/**
 * Surfaced when an `implement_fix` repair receipt fails its domain contract
 * (symbol granularity, named UTF-8 byte or collection budgets, or sanitization).
 * [payloadFreeReason] restates the violated rule with no instance value, diff
 * hunk, construct body, or source span — the only variant a retry prompt, blocked
 * reason, or ordinary log may carry.
 */
class InvalidFeatureTaskRuntimeRepairReceiptError(
  val fieldPath: String,
  val reason: String,
  val payloadFreeReason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task-runtime repair receipt fails at '${fieldPath.ifBlank { "<root>" }}': $reason",
  cause,
)

class InvalidFeatureTaskRuntimeRepairPlanError(
  val fieldPath: String,
  val reason: String,
  val payloadFreeReason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task-runtime repair plan fails at '${fieldPath.ifBlank { "<root>" }}': $reason",
  cause,
)

class InvalidFeatureTaskRuntimeFindingVerificationRecordError(
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task-runtime finding verification record is invalid: $reason",
  cause,
)

/**
 * Surfaced when a feature-task-runtime planning projection (preplanning digest, executable plan,
 * plan commitment, or implementation receipt) fails the canonical planning-projections schema.
 * Mirrors [InvalidReviewContextSchemaError]; the dedicated subclass keeps the four concrete bounded
 * projections distinguishable from the generic handoff envelope and audit repair plan in logs/tests.
 */
class InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
  val sourceLabel: String,
  val reason: String,
  val projectionName: String? = null,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task-runtime planning projection '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
  cause,
)

/**
 * Surfaced when the durable feature-task-runtime checkpoint-identity history fails the canonical
 * checkpoint-identity schema. The history is the only durable authority for what a checkpoint commit
 * was allowed to own, so a malformed record must never round-trip: reading one quarantines and
 * regenerates the identity store rather than letting an unattributable commit pass as governed.
 */
class InvalidFeatureTaskRuntimeCheckpointIdentitySchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task-runtime checkpoint identity '${sourceLabel.ifBlank { "<unknown>" }}' fails schema " +
    "validation: $reason",
  cause,
)

/**
 * Surfaced when a durable checkpoint-identity store was written under a contract version this
 * runtime cannot read. Distinct from a malformed record at the current version: a version mismatch
 * is repairable in band (quarantine the store, regenerate from the next checkpoint forward), while
 * corruption at the current version is not. Callers branch on the type, so both versions are carried
 * as typed fields rather than only in the message.
 *
 * Subclasses [InvalidWorkflowStateSchemaError] deliberately: the run loop, workflow service, IDE
 * status service, and goal-runner stores all catch that type, and a sibling class would turn a loud
 * rejection into an uncaught crash at those seams.
 */
class InvalidFeatureTaskRuntimeCheckpointIdentityVersionError(
  val expectedContractVersion: String,
  val actualContractVersion: String,
  cause: Throwable? = null,
) : InvalidWorkflowStateSchemaError(
  "Feature-task-runtime checkpoint-identity record uses unsupported contract version " +
    "'${actualContractVersion.ifBlank { "<absent>" }}'; this runtime reads " +
    "'$expectedContractVersion'. The store is quarantined and regenerated rather than reinterpreted.",
  cause,
)

/**
 * Surfaced when the durable feature-task-runtime quarantine record (the append-only list of
 * rejected upstream records) fails the canonical quarantine schema. Keeps the private evidence
 * store's parse seam loud so a malformed quarantine artifact never rounds trips silently.
 */
class InvalidFeatureTaskRuntimeQuarantineSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task-runtime quarantine record '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
  cause,
)

/**
 * Surfaced when the durable feature-task-runtime implementation-attempt history (the append-only
 * bounded receipt store the semantic continuation projection is reconstructed from) fails the
 * canonical implementation-attempt schema. Keeps that parse seam loud so a malformed attempt store
 * can never be silently best-effort decoded into a continuation projection that understates the
 * still-open obligations.
 */
class InvalidFeatureTaskRuntimeImplementationAttemptSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task-runtime implementation attempt '${sourceLabel.ifBlank { "<unknown>" }}' fails schema " +
    "validation: $reason",
  cause,
)

class InvalidFeatureTaskRuntimePhaseHandoffSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task-runtime phase handoff '$sourceLabel' fails schema validation: $reason",
  cause,
)

class InvalidFeatureTaskRuntimePersistenceSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task-runtime persistence record '$sourceLabel' fails schema validation: $reason",
  cause,
)

class InvalidFeatureTaskRuntimeProjectionMeasurementSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task-runtime projection measurement '$sourceLabel' fails schema validation: $reason",
  cause,
)

class InvalidFeatureTaskRuntimeSharedEvidenceProjectionSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task-runtime shared evidence projection '$sourceLabel' fails schema validation: $reason",
  cause,
)

class InvalidFeatureTaskRuntimeBuildReceiptSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
  val payloadFreeReason: String? = null,
  val failureCode: String = "schema_invalid",
) : ShellContentContractException(
  "Feature-task-runtime build receipt '$sourceLabel' fails schema validation: $reason",
  cause,
)

/**
 * Surfaced when a feature-task-runtime path would enter a gated phase before its gating phase
 * settled with the required verdict — for example entering `review` before `audit` reached
 * `satisfied`. The message names the attempted phase, the gating phase, the required verdict, and
 * the observed state so the loud failure is diagnosable without reading the topology.
 */
class FeatureTaskRuntimePhaseOrderViolationError(
  val phaseId: String,
  val requiredPhaseId: String,
  val requiredVerdict: String,
  val observedVerdict: String?,
) : ShellContentContractException(
  "Feature-task-runtime phase '$phaseId' is unreachable until '$requiredPhaseId' settles with the verdict " +
    "'$requiredVerdict', but it settled with " +
    "'${observedVerdict ?: "<no completed verdict>"}'; the run fails loudly rather than silently advancing.",
)

/**
 * Surfaced when an operator decision cannot be applied — the subtask is not paused, carries no
 * durable review state, or the choice could not be persisted. Loud-failing here keeps a rejected
 * `retry_fix` from silently driving the loop as if the operator had never chosen.
 */
class FeatureTaskRuntimeOperatorDecisionRejectedError(
  val workflowId: String,
  val decision: String,
  val reason: String,
) : ShellContentContractException(
  "Operator decision '$decision' was rejected for workflow '$workflowId': $reason",
)

class InvalidFeatureTaskExecutionIdentitySchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task execution identity '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
  cause,
)

class InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task runtime worker ownership '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
  cause,
)

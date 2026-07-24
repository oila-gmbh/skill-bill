package skillbill.error

open class ShellContentContractException(
  message: String,
  cause: Throwable? = null,
) : SkillBillRuntimeException(message, cause)

class MissingManifestError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class InvalidManifestSchemaError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class ReviewCompositionCycleError(message: String) : ShellContentContractException(message)

class AmbiguousLaneOwnershipError(message: String) : ShellContentContractException(message)

class IncompatibleCompositionContractError(message: String) : ShellContentContractException(message)

class MissingCompositionLayerError(message: String) : ShellContentContractException(message)

class InvalidAgentAddonSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Agent add-on '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
  cause,
)

class MissingAgentAddonDeclarationError(
  val slug: String,
  val expectedRoot: String,
) : ShellContentContractException(
  "Required agent add-on '$slug' was not found under '$expectedRoot'.",
)

class InvalidAgentAddonDeliveryTargetError(
  val slug: String,
  val target: String,
  val reason: String,
) : ShellContentContractException("Agent add-on '$slug' has invalid delivery target '$target': $reason")

class AgentAddonPointerCollisionError(
  val pointerName: String,
) : ShellContentContractException("Agent add-on pointer '$pointerName' collides in the portable staging namespace.")

class InvalidAgentAddonSelectionError(message: String) : ShellContentContractException(message)

class AgentAddonSelectionDriftError(
  val slug: String,
  val sourceIdentity: String,
) : ShellContentContractException(
  "Selected agent add-on '$slug' changed at '$sourceIdentity'; start a new run to accept the new content.",
)

class InvalidReviewContextSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Review context '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
  cause,
)

/**
 * SKILL-48 Subtask 2a: surfaced when a `WorkflowStateSnapshot` fails the
 * canonical `orchestration/contracts/workflow-state-schema.yaml` Draft
 * 2020-12 schema. The message carries the dotted field path of the first
 * offending value so callers and tests can pinpoint the regression
 * without parsing raw networknt validator output. Mirrors
 * [InvalidManifestSchemaError]; the dedicated subclass keeps workflow
 * parse-seam failures distinguishable from platform-pack manifest
 * failures in logs and tests.
 */
class InvalidWorkflowStateSchemaError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class InvalidWorkListRowError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class WorkflowIssueKeyConflictError(
  val workflowId: String,
  val persistedIssueKey: String,
  val requestedIssueKey: String,
) : ShellContentContractException(
  "Workflow '$workflowId' is already associated with issue key '$persistedIssueKey', not '$requestedIssueKey'.",
)

/**
 * SKILL-51: surfaced when a parent decomposition manifest fails the
 * canonical `orchestration/contracts/decomposition-manifest-schema.yaml`
 * Draft 2020-12 schema or its Kotlin-enforced coherence checks. The
 * composed message carries the source label and the violation reason
 * so decomposition write/read seams fail loudly without conflating this
 * contract with durable workflow-state snapshots.
 */
class InvalidDecompositionManifestSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Decomposition manifest '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
  cause,
)

/**
 * Surfaced when a feature-task-runtime phase output fails the canonical
 * phase-output schema. The message carries the source label and violation
 * reason.
 */
enum class FeatureTaskRuntimePhaseOutputFailureKind { MALFORMED, SCHEMA_INVALID }

class InvalidFeatureTaskRuntimePhaseOutputSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
  val failureKind: FeatureTaskRuntimePhaseOutputFailureKind =
    FeatureTaskRuntimePhaseOutputFailureKind.SCHEMA_INVALID,
) : ShellContentContractException(
  "Feature-task-runtime phase output '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
  cause,
)

/** Why a handoff projection was rejected before an agent was launched. */
enum class FeatureTaskRuntimeHandoffProjectionFailureKind {
  MISSING_REQUIRED_SOURCE,
  MALFORMED_FIELD,
  UNSUPPORTED_CONTRACT_VERSION,
  UNDECLARED_FIELD,
  DUPLICATE_PROJECTION_NAME,
  BUDGET_OVERFLOW,
  INVALID_COMPACT_REFERENCE,
  CHECKPOINT_POLICY_VIOLATION,
  SCHEMA_INVALID,
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

class InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task-runtime audit repair plan '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
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
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task-runtime planning projection '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
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

/**
 * SKILL-48 Subtask 2b: surfaced when an `InstallPlan` wire payload fails
 * the canonical `orchestration/contracts/install-plan-schema.yaml` Draft
 * 2020-12 schema. The composed message carries the dotted field path of
 * the first offending value so callers and tests can pinpoint the
 * regression without parsing raw networknt validator output. Mirrors
 * [InvalidWorkflowStateSchemaError]; the dedicated subclass keeps
 * install-plan parse-seam failures distinguishable from workflow-state
 * and platform-pack failures in logs and tests.
 */
class InvalidInstallPlanSchemaError(
  val fieldPath: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Install plan fails schema validation at '${fieldPath.ifBlank { "<root>" }}': $reason",
  cause,
)

/**
 * SKILL-48 Subtask 2c: surfaced when a native-agent composition source
 * fails the canonical
 * `orchestration/contracts/native-agent-composition-schema.yaml` Draft
 * 2020-12 schema. The composed message carries the source label (the
 * on-disk path or another caller-supplied identifier) and the
 * collected violation messages so callers and tests can pinpoint the
 * regression without parsing raw networknt validator output. Mirrors
 * [InvalidInstallPlanSchemaError]; the dedicated subclass keeps
 * native-agent parse-seam failures distinguishable from install-plan,
 * workflow-state, and platform-pack failures in logs and tests.
 */
class InvalidNativeAgentCompositionSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Native agent composition source '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
  cause,
)

/**
 * SKILL-48 Subtask 2d: surfaced when a telemetry event envelope fails
 * the canonical `orchestration/contracts/telemetry-event-schema.yaml`
 * Draft 2020-12 schema. The composed message carries the dotted
 * `fieldPath` of the first offending value AND the offending
 * `eventName` (nullable: unknown-event-name violations may report a
 * null name) so callers and tests can grep telemetry parse-seam
 * regressions by event name without parsing raw networknt validator
 * output. Mirrors [InvalidInstallPlanSchemaError]; the dedicated
 * subclass keeps telemetry parse-seam failures distinguishable from
 * install-plan, workflow-state, native-agent composition, and
 * platform-pack failures in logs and tests.
 */
class InvalidTelemetryEventSchemaError(
  val fieldPath: String,
  val eventName: String?,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Telemetry event '${eventName ?: "<unknown>"}' fails schema validation at " +
    "'${fieldPath.ifBlank { "<root>" }}': $reason",
  cause,
)

/**
 * Surfaced when a durable goal-observability event stored in workflow
 * artifacts_json fails the canonical
 * `orchestration/contracts/goal-observability-event-schema.yaml` Draft
 * 2020-12 schema. The composed message carries the artifact/source
 * label plus the offending field path so malformed latest-event or
 * run-history records fail loudly at the workflow artifact seam.
 */
class InvalidGoalObservabilityEventSchemaError(
  val sourceLabel: String,
  val fieldPath: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Goal observability event '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation at " +
    "'${fieldPath.ifBlank { "<root>" }}': $reason",
  cause,
)

/**
 * SKILL-64 Subtask 3: surfaced when a durable goal declared-progress event
 * stored in workflow artifacts_json fails the canonical
 * `orchestration/contracts/goal-progress-event-schema.yaml` Draft 2020-12
 * schema. The composed message carries the artifact/source label plus the
 * offending field path so malformed declared-progress records fail loudly at
 * the workflow artifact seam, distinct from goal-observability event failures.
 */
class InvalidGoalProgressEventSchemaError(
  val sourceLabel: String,
  val fieldPath: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Goal progress event '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation at " +
    "'${fieldPath.ifBlank { "<root>" }}': $reason",
  cause,
)

class InvalidGoalSubtaskReviewStateSchemaError(
  val sourceLabel: String,
  val fieldPath: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Goal subtask review state '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation at " +
    "'${fieldPath.ifBlank { "<root>" }}': $reason",
  cause,
)

class InvalidGoalPlanningPreparationSchemaError(
  val sourceLabel: String,
  val fieldPath: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Goal planning preparation '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation at " +
    "'${fieldPath.ifBlank { "<root>" }}': $reason",
  cause,
)

class IncompatibleGoalPlanningPreparationRecoveryError(
  val workflowId: String,
  val subtaskId: Int,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Goal planning preparation '$workflowId' subtask $subtaskId cannot be recovered: $reason",
  cause,
)

class MissingInstallSelectionRecordError(
  val path: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Install selection record is missing at '${path.ifBlank { "<unknown>" }}'.",
  cause,
)

class UnreadableInstallSelectionRecordError(
  val path: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Install selection record at '${path.ifBlank { "<unknown>" }}' cannot be read.",
  cause,
)

class MalformedInstallSelectionRecordError(
  val path: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Install selection record at '${path.ifBlank { "<unknown>" }}' is malformed: $reason",
  cause,
)

/**
 * SKILL-76 Subtask 2: surfaced when the durable baseline manifest at
 * `~/.skill-bill/baseline-manifest.json` exists but cannot be read or parsed
 * (IO/permission failure, malformed JSON, unknown/blank keys, or an unsupported
 * contract version). The message names the offending path so reconciliation
 * fails loudly at the read seam instead of silently falling back. Mirrors
 * [UnreadableInstallSelectionRecordError]; the dedicated subclass keeps baseline
 * read failures distinguishable from install-selection failures.
 */
class UnreadableBaselineManifestError(
  val path: String,
  val reason: String? = null,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Baseline manifest at '${path.ifBlank { "<unknown>" }}' cannot be read" +
    (reason?.let { ": $it." } ?: "."),
  cause,
)

/**
 * SKILL-76 Subtask 2: surfaced when per-skill reconciliation classification cannot
 * be completed loudly — e.g. an upstream/local source root that is required for a
 * computed outcome is unreadable, or a reconciliation invariant is violated. The
 * message names the offending skill-relative path and the failure reason so the
 * shell aborts the whole install with a clear message rather than half-applying.
 */
class ReconciliationConflictError(
  val skillRelativePath: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Reconciliation failed for skill '${skillRelativePath.ifBlank { "<unknown>" }}': $reason",
  cause,
)

/**
 * SKILL-76 Subtask 2: surfaced when the runtime-owned per-skill reconcile APPLY is
 * invoked while the computed plan still has unresolved both-changed conflicts and the
 * caller did NOT pass `--accept-conflicts`. Apply refuses and changes NOTHING on disk,
 * so AC-7 "abort changes nothing" holds even if the shell mis-calls. The message lists
 * the conflicting skill-relative paths so the failure is actionable.
 */
class ReconciliationApplyRefusedError(
  val conflictPaths: List<String>,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Refusing to apply reconciliation: ${conflictPaths.size} unresolved both-changed conflict(s) " +
    "(${conflictPaths.joinToString(", ").ifBlank { "<none>" }}) and --accept-conflicts was not set. " +
    "Nothing was changed.",
  cause,
)

/**
 * SKILL-71 Subtask 1 (AC3): surfaced when the repo-local `.skill-bill/config.yaml`
 * exists but cannot be read (IO/permission failure). The message names the
 * offending file path so config-load failures fail loudly at the read seam.
 * Mirrors [UnreadableInstallSelectionRecordError]; the dedicated subclass keeps
 * repo-local config failures distinguishable from install-selection failures.
 */
class UnreadableRepoLocalConfigError(
  val path: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Repo-local config at '${path.ifBlank { "<unknown>" }}' cannot be read.",
  cause,
)

/**
 * SKILL-71 Subtask 1 (AC3): surfaced when the repo-local `.skill-bill/config.yaml`
 * is malformed YAML, or carries an unknown/invalid value for a known key. The
 * composed message names the file path AND the offending key/value so callers
 * and tests can pinpoint the regression without guessing which key failed.
 */
class MalformedRepoLocalConfigError(
  val path: String,
  val key: String,
  val value: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Repo-local config at '${path.ifBlank { "<unknown>" }}' is malformed: " +
    "key '${key.ifBlank { "<root>" }}' value '$value' $reason",
  cause,
)

class MalformedMachineConfigError(
  val path: String,
  val key: String,
  val value: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Machine config at '${path.ifBlank { "<unknown>" }}' is malformed: " +
    "key '${key.ifBlank { "<root>" }}' value '$value' $reason",
  cause,
)

class ContractVersionMismatchError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

/**
 * SKILL-102 subtask 1: surfaced at install-staging time when an authored file
 * already occupies the would-be sidecar name inside the parent skill's source
 * directory. Follows the generated-artifact guard pattern
 * (`skillbill.scaffold.pointer.GeneratedArtifactGuard`); the dedicated subclass
 * keeps sidecar collision failures distinguishable from declaration failures.
 */
class InternalSkillSidecarCollisionError(
  val parentSkillName: String,
  val internalSkillName: String,
  val sidecarRelativePath: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Internal skill '$internalSkillName' cannot be staged as sidecar " +
    "'$sidecarRelativePath' inside parent '$parentSkillName' skill directory: " +
    "another staged or authored file already claims that path. Rename or remove the conflicting file.",
  cause,
)

class InvalidAuthoredSkillSidecarError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class InvalidReviewSkillStructureError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class MissingContentFileError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class MissingRequiredSectionError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class InvalidDescriptorSectionError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class InvalidExecutionSectionError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class InvalidCeremonySectionError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class MissingShellCeremonyFileError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class InvalidSkillMdShapeError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class InvalidNativeAgentLinkInventorySchemaError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class MissingInstalledNativeAgentError(
  val logicalName: String,
  val provider: String,
  val expectedPath: String,
  val reason: String,
  val repairCommand: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Native agent '$logicalName' for provider '$provider' failed preflight at '$expectedPath': $reason. " +
    "Repair with: $repairCommand",
  cause,
)

/**
 * SKILL-102 subtask 1: surfaced when an internal-skill classification is invalid. The composed
 * message names the offending skill, the declared parent, and the rule violated so authors can
 * pinpoint the regression without re-parsing frontmatter. Mirrors [InvalidSkillMdShapeError];
 * the dedicated subclass keeps internal-skill classification failures distinguishable from
 * wrapper-shape failures in logs and tests.
 */
class InvalidInternalSkillClassificationError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

/**
 * SKILL-104 (PD8): surfaced at install-plan time when the platform selection includes a pack whose
 * manifest declares a required `code_review_composition.baseline_layers` entry in an unselected
 * pack. Selecting the baseline pack (or `ALL`) resolves it; the shell never silently auto-includes
 * a baseline. Mirrors [ReconciliationConflictError]; the dedicated subclass keeps baseline
 * co-presence failures distinguishable from declaration and reconciliation failures in logs/tests.
 */
class MissingBaselinePlatformSelectionError(
  val selectingSlug: String,
  val requiredBaselineSlug: String,
  val declaringManifestPath: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Platform pack '$selectingSlug' declares a required baseline layer on '$requiredBaselineSlug' " +
    "(declared in '$declaringManifestPath'), but '$requiredBaselineSlug' is not in the selection. " +
    "Select '$requiredBaselineSlug' (or use platform mode ALL) so the baseline sidecar is present " +
    "at review time.",
  cause,
)

open class ScaffoldError(
  message: String,
  cause: Throwable? = null,
) : SkillBillRuntimeException(message, cause)

class ScaffoldPayloadVersionMismatchError(
  message: String,
  cause: Throwable? = null,
) : ScaffoldError(message, cause)

class InvalidScaffoldPayloadError(
  message: String,
  cause: Throwable? = null,
) : ScaffoldError(message, cause)

class RetiredScaffoldKindError(
  message: String,
  cause: Throwable? = null,
) : ScaffoldError(message, cause)

class UnknownSkillKindError(
  message: String,
  cause: Throwable? = null,
) : ScaffoldError(message, cause)

class UnknownPreShellFamilyError(
  message: String,
  cause: Throwable? = null,
) : ScaffoldError(message, cause)

class MissingPlatformPackError(
  message: String,
  cause: Throwable? = null,
) : ScaffoldError(message, cause)

class MissingSupportingFileTargetError(
  message: String,
  cause: Throwable? = null,
) : ScaffoldError(message, cause)

class SkillAlreadyExistsError(
  message: String,
  cause: Throwable? = null,
) : ScaffoldError(message, cause)

class ScaffoldValidatorError(
  message: String,
  cause: Throwable? = null,
) : ScaffoldError(message, cause)

class ScaffoldRollbackError(
  message: String,
  cause: Throwable? = null,
) : ScaffoldError(message, cause)

class UnaddressedFindingsLedgerAbsentError(message: String) : ShellContentContractException(message)

class InvalidUnaddressedFindingsLedgerSchemaError(message: String) : ShellContentContractException(message)

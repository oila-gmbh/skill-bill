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
class InvalidFeatureTaskRuntimePhaseOutputSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task-runtime phase output '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
  cause,
)

class InvalidTeamBundleSchemaError(
  val sourceLabel: String,
  val fieldPath: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Team bundle '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation at " +
    "'${fieldPath.ifBlank { "<root>" }}': $reason",
  cause,
)

class InvalidTeamBundleChecksumError(
  val path: String,
  val expected: String,
  val actual: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Team bundle checksum mismatch for '${path.ifBlank { "<unknown>" }}': expected $expected but computed $actual.",
  cause,
)

class TeamBundleContentHashMismatchError(
  val bundleId: String,
  val expected: String,
  val actual: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Team bundle '${bundleId.ifBlank { "<unknown>" }}' content_hash mismatch: expected $expected but computed $actual.",
  cause,
)

class InvalidTeamBundleRegistryChannelError(
  val channel: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Invalid team bundle registry channel '${channel.ifBlank { "<blank>" }}'. Supported registry channels: " +
    "development, beta, stable.",
  cause,
)

class GeneratedTeamBundleArtifactEntryError(
  val entryName: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Team bundle archive entry '${entryName.ifBlank { "<unknown>" }}' is not a valid governed source: $reason",
  cause,
)

class MissingPreviousTeamBundleError(
  cause: Throwable? = null,
) : ShellContentContractException("No previous team bundle is recorded for rollback.", cause)

class MissingPreviousTeamBundleSourceError(
  val path: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Previous team bundle source is missing at '${path.ifBlank { "<unknown>" }}'.",
  cause,
)

class TeamBundleSyncInstallFailedError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class TeamBundleRollbackIncompleteError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

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
    "an authored file already occupies that path. Rename or remove the authored file.",
  cause,
)

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

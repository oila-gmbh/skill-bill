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

class ContractVersionMismatchError(
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

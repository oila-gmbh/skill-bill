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

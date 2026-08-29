package skillbill.error

class MissingManifestError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class InvalidManifestSchemaError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

/**
 * Malformed `validation_gate` declaration on a platform pack. Distinct from a missing
 * (null) declaration, which is a surfaced degradation to agent-run validate — never
 * from a malformed shape, which must loud-fail.
 */
class InvalidValidationGateDeclarationError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class ReviewCompositionCycleError(message: String) : ShellContentContractException(message)

class AmbiguousLaneOwnershipError(message: String) : ShellContentContractException(message)

class IncompatibleCompositionContractError(message: String) : ShellContentContractException(message)

class MissingCompositionLayerError(message: String) : ShellContentContractException(message)

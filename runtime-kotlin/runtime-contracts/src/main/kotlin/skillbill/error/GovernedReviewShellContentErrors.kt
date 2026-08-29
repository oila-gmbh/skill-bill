package skillbill.error

class UnaddressedFindingsLedgerAbsentError(message: String) : ShellContentContractException(message)

class InvalidUnaddressedFindingsLedgerSchemaError(message: String) : ShellContentContractException(message)

class GovernedReviewEvidenceTransportError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class GovernedReviewLaunchCapabilityError(
  val provider: String,
  val capability: String,
) : ShellContentContractException(
  "Agent '$provider' cannot launch a governed review: missing capability '$capability'.",
)

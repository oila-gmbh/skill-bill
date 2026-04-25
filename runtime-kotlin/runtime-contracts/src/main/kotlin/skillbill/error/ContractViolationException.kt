package skillbill.error

import skillbill.contracts.RuntimeContract

/** Raised when a governed contract is violated by Kotlin runtime code. */
class ContractViolationException(
  contract: RuntimeContract,
  detail: String,
  cause: Throwable? = null,
) :
  SkillBillRuntimeException(
    message = "Contract '${contract.name}' (${contract.version}) violation: $detail",
    cause = cause,
  )

package skillbill.contracts

import skillbill.error.SkillBillRuntimeException

/** Shared success/failure wrapper for early subsystem ports. */
sealed interface ContractResult<out T : Any> {
  data class Success<T : Any>(val value: T) : ContractResult<T>

  data class Failure(val error: SkillBillRuntimeException) : ContractResult<Nothing>
}

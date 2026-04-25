package skillbill.contracts

/** Generic payload wrapper for contract-bound runtime interactions. */
data class ContractEnvelope<T : Any>(
  val contract: RuntimeContract,
  val payload: T,
)

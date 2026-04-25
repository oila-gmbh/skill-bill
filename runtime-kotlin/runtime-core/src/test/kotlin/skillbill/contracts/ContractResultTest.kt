package skillbill.contracts

import skillbill.error.ContractViolationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ContractResultTest {
  @Test
  fun `failure preserves the contract violation exception`() {
    val contract =
      object : RuntimeContract {
        override val name: String = "governed-loader"
        override val version: String = "1.1"
      }

    val result: ContractResult<ContractEnvelope<String>> =
      ContractResult.Failure(
        ContractViolationException(
          contract = contract,
          detail = "missing content file",
        ),
      )

    val failure = assertIs<ContractResult.Failure>(result)
    assertEquals(
      "Contract 'governed-loader' (1.1) violation: missing content file",
      failure.error.message,
    )
  }
}

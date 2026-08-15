package skillbill.contracts.review

import skillbill.error.InvalidReviewContextSchemaError
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReviewContextStageDefinitionSeamTest {
  @Test
  fun `each new parse seam names its definition on an invalid payload`() {
    val verification = assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewContextSchemaValidator.validateVerificationLaunch(
        mapOf("contract_version" to REVIEW_CONTEXT_CONTRACT_VERSION, "kind" to "verification_launch"),
        "verification",
      )
    }
    val adjudication = assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewContextSchemaValidator.validateAdjudicationLaunch(
        mapOf("contract_version" to REVIEW_CONTEXT_CONTRACT_VERSION, "kind" to "adjudication_launch"),
        "adjudication",
      )
    }
    val verdict = assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewContextSchemaValidator.validateFindingVerdict(
        mapOf("contract_version" to REVIEW_CONTEXT_CONTRACT_VERSION, "kind" to "finding_verdict"),
        "verdict",
      )
    }
    val projection = assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewContextSchemaValidator.validateSpecIntentProjection(emptyMap(), "projection")
    }
    assertTrue("for definition 'verification_launch'" in verification.message.orEmpty())
    assertTrue("for definition 'adjudication_launch'" in adjudication.message.orEmpty())
    assertTrue("for definition 'finding_verdict'" in verdict.message.orEmpty())
    assertTrue("for definition 'spec_intent_projection'" in projection.message.orEmpty())
  }
}

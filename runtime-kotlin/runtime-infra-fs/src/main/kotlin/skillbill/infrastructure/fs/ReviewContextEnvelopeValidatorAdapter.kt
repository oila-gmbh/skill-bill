package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.review.ReviewContextSchemaValidator
import skillbill.review.context.ReviewContextEnvelopeValidator

@Inject
class ReviewContextEnvelopeValidatorAdapter : ReviewContextEnvelopeValidator {
  override fun validate(envelope: Map<String, Any?>, sourceLabel: String) {
    ReviewContextSchemaValidator.validate(envelope, sourceLabel)
  }
}

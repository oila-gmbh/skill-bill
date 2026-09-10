package skillbill.review.context.model

import skillbill.error.InvalidReviewContextSchemaError

object ReviewEvidenceLimits {
  const val FIELD_CHARACTERS: Int = 1024
  const val FIELD_BYTES: Int = 4096
  const val REQUEST_BYTES: Int = 64 * 1024
  const val METADATA_BYTES: Int = 64 * 1024
  const val EXPANSION_LEDGER_BYTES: Int = 32 * 1024
  const val RESPONSE_PAYLOAD_BYTES: Int = 256 * 1024
  const val RESPONSE_FRAME_BYTES: Int = 2 * 1024 * 1024

  fun field(value: String) {
    if (value.isBlank() || value.codePointCount(0, value.length) > FIELD_CHARACTERS ||
      value.toByteArray(Charsets.UTF_8).size > FIELD_BYTES
    ) {
      throw InvalidReviewContextSchemaError(
        "review-evidence",
        "Evidence field is blank or exceeds its byte or character limit.",
      )
    }
  }
}

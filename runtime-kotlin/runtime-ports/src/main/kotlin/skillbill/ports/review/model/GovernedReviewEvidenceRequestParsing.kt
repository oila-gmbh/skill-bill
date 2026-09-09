package skillbill.ports.review.model

import skillbill.error.InvalidReviewContextSchemaError

internal const val REVIEW_DISCOVERY_CURSOR_CHARACTERS = 128

internal fun discoveryCursor(arguments: Map<String, Any?>): String? {
  if ("cursor" !in arguments) return null
  val cursor = arguments["cursor"] as? String
    ?: throw InvalidReviewContextSchemaError("review-discovery", "Cursor must be a string.")
  if (cursor.isBlank() || cursor.length > REVIEW_DISCOVERY_CURSOR_CHARACTERS) {
    throw InvalidReviewContextSchemaError("review-discovery", "Discovery bounds exceeded.")
  }
  return cursor
}

internal fun discoveryPageSize(arguments: Map<String, Any?>): Int {
  if ("page_size" !in arguments) return REVIEW_DISCOVERY_PAGE_SIZE
  val size = when (val value = arguments["page_size"]) {
    is Int -> value.toLong()
    is Long -> value
    else -> throw InvalidReviewContextSchemaError("review-discovery", "Page size must be an integer.")
  }
  if (size !in 1..REVIEW_DISCOVERY_PAGE_SIZE.toLong()) {
    throw InvalidReviewContextSchemaError("review-discovery", "Page size is outside its bounds.")
  }
  return size.toInt()
}

internal fun evidenceReadItems(arguments: Map<String, Any?>): List<*> {
  val items = arguments["requests"] as? List<*>
    ?: throw InvalidReviewContextSchemaError("review-evidence", "Read requests must be an array.")
  if (items.size !in 1..REVIEW_EVIDENCE_BATCH_SIZE) {
    throw InvalidReviewContextSchemaError("review-evidence", "Read batch exceeds its request bounds.")
  }
  return items
}

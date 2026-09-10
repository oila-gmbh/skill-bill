package skillbill.ports.review.model

import skillbill.error.GovernedReviewEvidenceTransportError
import skillbill.review.context.model.ReviewEvidenceLimits
import java.io.BufferedReader

private const val UTF8_SINGLE_BYTE_MAX = 0x7f
private const val UTF8_TWO_BYTE_MAX = 0x7ff
private const val UTF8_THREE_BYTE_WIDTH = 3

fun BufferedReader.readReviewEvidenceFrame(maxBytes: Int = ReviewEvidenceLimits.REQUEST_BYTES): String? {
  val frame = StringBuilder()
  var bytes = 0
  var previousHighSurrogate = false
  while (true) {
    val next = read()
    if (next == -1) return frame.toString().takeIf { it.isNotEmpty() }
    if (next == '\n'.code) return frame.toString().removeSuffix("\r")
    bytes += when {
      previousHighSurrogate && next.toChar().isLowSurrogate() -> 1
      next <= UTF8_SINGLE_BYTE_MAX -> 1
      next <= UTF8_TWO_BYTE_MAX -> 2
      else -> UTF8_THREE_BYTE_WIDTH
    }
    previousHighSurrogate = next.toChar().isHighSurrogate()
    if (bytes > maxBytes) throw GovernedReviewEvidenceTransportError("Governed evidence frame exceeds its byte limit.")
    frame.append(next.toChar())
  }
}

private const val REVIEW_REQUEST_ID_CHARACTERS = 128

fun validReviewEvidenceRequestId(id: Any?): Boolean {
  if (id == null) return true
  val supportedType = id is String || id is Number
  return supportedType && id.toString().length <= REVIEW_REQUEST_ID_CHARACTERS
}

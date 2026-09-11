package skillbill.launcher.review

import kotlinx.serialization.json.JsonObject
import skillbill.contracts.JsonSupport
import skillbill.error.ShellContentContractException
import skillbill.ports.review.NativeReviewOperationProtocol

internal fun handleGovernedReviewMethod(
  frame: JsonObject,
  id: Any?,
  protocol: NativeReviewOperationProtocol,
  dispatch: (Any?, String, Map<String, Any?>) -> String,
): String {
  val method = frame["method"]?.let(JsonSupport::jsonElementToValue)?.toString().orEmpty()
  val params = JsonSupport.anyToStringAnyMap(frame["params"]?.let(JsonSupport::jsonElementToValue)).orEmpty()
  return when (method) {
    "evidence/refused" -> {
      protocol.recordMalformedRequest()
      governedReviewEvidenceToolResponse(id, mapOf("recorded" to true))
    }
    "evidence/delivered" -> confirmGovernedReviewDelivery(id, params, protocol)
    "tools/call" -> {
      val name = params["name"]?.toString().orEmpty()
      val arguments = JsonSupport.anyToStringAnyMap(params["arguments"]).orEmpty()
      dispatch(id, name, arguments)
    }
    else -> {
      protocol.recordMalformedRequest()
      governedReviewEvidenceErrorResponse(
        id,
        GOVERNED_REVIEW_EVIDENCE_JSON_RPC_METHOD_NOT_FOUND,
        "Method not found: $method",
      )
    }
  }
}

private fun confirmGovernedReviewDelivery(
  id: Any?,
  params: Map<String, Any?>,
  protocol: NativeReviewOperationProtocol,
): String {
  val receipt = params["receipt"] as? String
    ?: return governedReviewEvidenceErrorResponse(
      id,
      GOVERNED_REVIEW_EVIDENCE_JSON_RPC_INVALID_PARAMS,
      "Missing receipt.",
    )
  return try {
    protocol.confirmDelivery(receipt)
    governedReviewEvidenceToolResponse(id, mapOf("confirmed" to true))
  } catch (error: ShellContentContractException) {
    governedReviewEvidenceErrorResponse(id, GOVERNED_REVIEW_EVIDENCE_JSON_RPC_INVALID_PARAMS, error.message.orEmpty())
  }
}

internal fun <T> NativeReviewOperationProtocol.decodeGovernedReviewRequest(decode: () -> T): T = try {
  decode()
} catch (error: ShellContentContractException) {
  recordMalformedRequest()
  throw error
} catch (error: IllegalArgumentException) {
  recordMalformedRequest()
  throw error
}

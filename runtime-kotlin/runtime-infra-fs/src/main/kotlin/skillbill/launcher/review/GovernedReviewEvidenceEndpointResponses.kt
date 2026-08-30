package skillbill.launcher.review

import skillbill.contracts.JsonSupport

private const val JSON_RPC_METHOD_NOT_FOUND = -32601
private const val JSON_RPC_INVALID_PARAMS = -32602

internal fun governedReviewEvidenceToolResponse(id: Any?, payload: Map<String, Any?>): String =
  JsonSupport.mapToJsonString(
    linkedMapOf(
      "jsonrpc" to "2.0",
      "id" to id,
      "result" to linkedMapOf(
        "content" to listOf(linkedMapOf("type" to "text", "text" to JsonSupport.mapToJsonString(payload))),
        "isError" to false,
      ),
    ),
  )

internal fun governedReviewEvidenceErrorResponse(id: Any?, code: Int, message: String): String =
  JsonSupport.mapToJsonString(
    linkedMapOf(
      "jsonrpc" to "2.0",
      "id" to id,
      "error" to linkedMapOf("code" to code, "message" to message),
    ),
  )

internal const val GOVERNED_REVIEW_EVIDENCE_JSON_RPC_METHOD_NOT_FOUND: Int = JSON_RPC_METHOD_NOT_FOUND
internal const val GOVERNED_REVIEW_EVIDENCE_JSON_RPC_INVALID_PARAMS: Int = JSON_RPC_INVALID_PARAMS

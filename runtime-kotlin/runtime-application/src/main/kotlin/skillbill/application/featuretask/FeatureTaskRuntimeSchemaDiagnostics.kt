package skillbill.application.featuretask

import skillbill.error.InvalidWorkflowStateSchemaError

internal fun redactedWorkflowStateFailure(error: InvalidWorkflowStateSchemaError): String {
  val type = error::class.simpleName.orEmpty()
  val message = error.message.orEmpty()
  return when {
    "malformed JSON" in message -> "$type: malformed JSON"
    "must decode to a JSON array" in message -> "$type: malformed JSON"
    "must decode to a JSON object" in message -> "$type: malformed JSON"
    else -> type
  }
}

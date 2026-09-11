package skillbill.infrastructure.sqlite.workflow

import skillbill.contracts.JsonSupport

internal fun decodeWorkflowStringList(rawValue: String?): List<String> =
  JsonSupport.parseArrayOrEmpty(rawValue.orEmpty()).mapNotNull { element -> element as? String }

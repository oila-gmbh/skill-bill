package skillbill.db.workflow

import skillbill.contracts.JsonCodec

internal fun decodeWorkflowStringList(rawValue: String?): List<String> =
  JsonCodec.parseArrayOrEmpty(rawValue.orEmpty()).mapNotNull { element -> element as? String }

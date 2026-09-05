package skillbill.application.decomposition

import skillbill.contracts.JsonCodec

internal fun decodeArtifactKeys(existingArtifactsJson: String, keys: Set<String>): Map<String, Any?> {
  if (keys.isEmpty()) return emptyMap()
  val root = JsonCodec.parseObjectOrNull(existingArtifactsJson) ?: return emptyMap()
  return buildMap {
    keys.forEach { key ->
      val element = root[key] ?: return@forEach
      put(key, JsonCodec.jsonElementToValue(element))
    }
  }
}

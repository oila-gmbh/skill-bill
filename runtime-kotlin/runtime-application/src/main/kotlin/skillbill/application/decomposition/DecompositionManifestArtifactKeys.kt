package skillbill.application.decomposition

import skillbill.contracts.JsonSupport

internal fun decodeArtifactKeys(existingArtifactsJson: String, keys: Set<String>): Map<String, Any?> {
  if (keys.isEmpty()) return emptyMap()
  val root = JsonSupport.parseObjectOrNull(existingArtifactsJson) ?: return emptyMap()
  return buildMap {
    keys.forEach { key ->
      val element = root[key] ?: return@forEach
      put(key, JsonSupport.jsonElementToValue(element))
    }
  }
}

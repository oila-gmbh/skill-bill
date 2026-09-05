package skillbill.ports.workflow.decomposition.runtime

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonCodec

@OpenBoundaryMap("Persisted workflow artifact JSON sparsely decoded for progress polling")
fun decodeArtifactKeys(existingArtifactsJson: String, keys: Set<String>): Map<String, Any?> {
  if (keys.isEmpty()) return emptyMap()
  val root = JsonCodec.parseObjectOrNull(existingArtifactsJson) ?: return emptyMap()
  return buildMap {
    keys.forEach { key ->
      val element = root[key] ?: return@forEach
      put(key, JsonCodec.jsonElementToValue(element))
    }
  }
}

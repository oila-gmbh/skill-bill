package skillbill.workflow.taskruntime.model

import skillbill.contracts.JsonCodec

internal object FeatureTaskRuntimeProjectionCanonicalizerMapOps {
  fun mapEntries(value: Any?, transform: (Int, Map<String, Any?>) -> Map<String, Any?>): Any? {
    val list = value as? List<*> ?: return value
    return list.mapIndexed { index, entry ->
      val entryMap = entry as? Map<*, *> ?: return@mapIndexed entry
      transform(index, entryMap.stringKeyedView())
    }
  }

  fun mapObject(value: Any?, transform: (Map<String, Any?>) -> Map<String, Any?>): Any? {
    val map = value as? Map<*, *> ?: return value
    return transform(map.stringKeyedView())
  }

  fun Map<*, *>.stringKeyedView(): Map<String, Any?> = JsonCodec.anyToStringAnyMap(this) ?: LinkedHashMap()
}

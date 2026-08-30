package skillbill.workflow.taskruntime.model

import skillbill.contracts.JsonSupport

internal object FeatureTaskRuntimeProjectionCanonicalizerMutations {
  fun discardUnknownKeys(
    map: Map<String, Any?>,
    governedKeys: Set<String>,
    fieldPath: String,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
  ): Map<String, Any?> {
    if (map.keys.all { it in governedKeys }) return map
    val retained = LinkedHashMap<String, Any?>(map.size)
    map.forEach { (key, value) ->
      if (key in governedKeys) {
        retained[key] = value
      } else {
        records += textFreeRecord(
          "$fieldPath.${key.take(MAX_RECORDED_ID_LENGTH)}",
          listOf(FeatureTaskRuntimeProjectionCanonicalizationTransform.UNKNOWN_KEY_DISCARDED),
        )
      }
    }
    return retained
  }

  fun adoptedProseKey(
    map: Map<String, Any?>,
    governedKeys: Set<String>,
    proseKey: String,
    fieldPath: String,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
  ): Map<String, Any?> {
    if (map.containsKey(proseKey)) return map
    val donor = map.keys.singleOrNull { it !in governedKeys } ?: return map
    val prose = (map[donor] as? String)?.trim()?.takeIf(String::isNotEmpty) ?: return map
    records += textFreeRecord(
      "$fieldPath.$proseKey",
      listOf(FeatureTaskRuntimeProjectionCanonicalizationTransform.MISNAMED_KEY_ADOPTED),
    )
    val adopted = LinkedHashMap<String, Any?>(map.size)
    map.forEach { (key, value) -> if (key != donor) adopted[key] = value }
    adopted[proseKey] = prose
    return adopted
  }

  fun discardUnknownKeysInEntries(
    value: Any?,
    governedKeys: Set<String>,
    fieldPath: String,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
  ): Any? {
    val list = value as? List<*> ?: return value
    return list.mapIndexed { index, entry ->
      val stringKeyed = JsonSupport.anyToStringAnyMap(entry) ?: return@mapIndexed entry
      discardUnknownKeys(stringKeyed, governedKeys, "$fieldPath[$index]", records)
    }
  }

  fun promotedReconciliationEvidence(
    value: Any?,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
  ): Any? {
    val evidence = (value as? String)?.trim()?.takeIf(String::isNotEmpty) ?: return value
    records += textFreeRecord(
      "reconciliation_evidence",
      listOf(FeatureTaskRuntimeProjectionCanonicalizationTransform.SCALAR_PROMOTED_TO_OBJECT),
    )
    return linkedMapOf<String, Any?>("reconciled" to true, "evidence" to evidence)
  }

  fun trimNonBlank(
    map: Map<String, Any?>,
    key: String,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    fieldPath: String,
  ): Map<String, Any?> {
    if (key !in map) return map
    val result = LinkedHashMap<String, Any?>(map)
    result[key] = trimNonBlankValue(map[key], records, fieldPath)
    return result
  }

  fun trimNonBlankValue(
    value: Any?,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    fieldPath: String,
  ): Any? {
    val raw = value as? String ?: return value
    val trimmed = raw.trim()
    if (trimmed != raw) {
      records += textFreeRecord(fieldPath, listOf(FeatureTaskRuntimeProjectionCanonicalizationTransform.TRIMMED))
    }
    return trimmed
  }

  fun recordIdChange(
    raw: String,
    canonical: String,
    fieldPath: String,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
  ) {
    if (canonical == raw) return
    records += FeatureTaskRuntimeProjectionCanonicalizationRecord(
      fieldPath = fieldPath,
      transforms = listOf(FeatureTaskRuntimeProjectionCanonicalizationTransform.TASK_ID_NORMALIZED),
      originalId = raw.take(MAX_RECORDED_ID_LENGTH),
      canonicalId = canonical.take(MAX_RECORDED_ID_LENGTH),
    )
  }

  fun textFreeRecord(fieldPath: String, transforms: List<FeatureTaskRuntimeProjectionCanonicalizationTransform>) =
    FeatureTaskRuntimeProjectionCanonicalizationRecord(fieldPath = fieldPath, transforms = transforms)
}

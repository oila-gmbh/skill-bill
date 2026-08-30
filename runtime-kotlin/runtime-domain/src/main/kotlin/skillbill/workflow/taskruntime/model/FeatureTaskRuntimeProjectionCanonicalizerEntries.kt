package skillbill.workflow.taskruntime.model

internal object FeatureTaskRuntimeProjectionCanonicalizerEntries {
  fun canonicalizeTaskEntry(
    entry: Map<String, Any?>,
    declaredIds: Map<String, String>,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    index: Int,
  ): Map<String, Any?> {
    val governed = FeatureTaskRuntimeProjectionCanonicalizerMutations.discardUnknownKeys(
      entry,
      FEATURE_TASK_RUNTIME_PLAN_TASK_KEYS,
      "tasks[$index]",
      records,
    )
    val result = LinkedHashMap<String, Any?>(governed.size)
    governed.forEach { (key, value) ->
      result[key] = when (key) {
        "task_id" -> canonicalizeDeclaredId(value, records, "tasks[$index].task_id")
        "depends_on" -> canonicalizeReferenceIds(value, declaredIds, records, "tasks[$index].depends_on")
        "description" -> canonicalizeCompactSummary(value, records, "tasks[$index].description")
        in FEATURE_TASK_RUNTIME_NONBLANK_STRING_LIST_KEYS ->
          trimStringList(value, records, "tasks[$index].$key")
        else -> value
      }
    }
    return result
  }

  fun canonicalizeCommitmentEntry(
    entry: Map<String, Any?>,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    index: Int,
  ): Map<String, Any?> {
    val governed = FeatureTaskRuntimeProjectionCanonicalizerMutations.discardUnknownKeys(
      entry,
      FEATURE_TASK_RUNTIME_TASK_COMMITMENT_KEYS,
      "task_commitments[$index]",
      records,
    )
    val result = LinkedHashMap<String, Any?>(governed.size)
    governed.forEach { (key, value) ->
      result[key] = when (key) {
        "task_id" -> canonicalizeDeclaredId(value, records, "task_commitments[$index].task_id")
        in FEATURE_TASK_RUNTIME_NONBLANK_STRING_LIST_KEYS ->
          trimStringList(value, records, "task_commitments[$index].$key")
        else -> value
      }
    }
    return result
  }

  fun canonicalizeDeviationEntry(
    entry: Map<String, Any?>,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    index: Int,
  ): Map<String, Any?> {
    val governed = FeatureTaskRuntimeProjectionCanonicalizerMutations.discardUnknownKeys(
      entry,
      FEATURE_TASK_RUNTIME_DEVIATION_KEYS,
      "deviations[$index]",
      records,
    )
    val result = LinkedHashMap<String, Any?>(governed.size)
    governed.forEach { (key, value) ->
      result[key] = when (key) {
        "ref" -> FeatureTaskRuntimeProjectionCanonicalizerMutations.trimNonBlankValue(
          value,
          records,
          "deviations[$index].ref",
        )
        "note" -> canonicalizeCompactSummary(value, records, "deviations[$index].note")
        else -> value
      }
    }
    return result
  }

  fun canonicalizeDeclaredId(
    value: Any?,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    fieldPath: String,
  ): Any? {
    val raw = value as? String ?: return value
    val canonical = FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId(raw)
    FeatureTaskRuntimeProjectionCanonicalizerMutations.recordIdChange(raw, canonical, fieldPath, records)
    return canonical
  }

  fun canonicalizeReferenceIds(
    value: Any?,
    declaredIds: Map<String, String>,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    fieldPath: String,
  ): Any? {
    val list = value as? List<*> ?: return value
    return list.mapIndexed { index, raw ->
      if (raw !is String) return@mapIndexed raw
      val canonical = declaredIds[raw]
        ?: FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId(raw)
      FeatureTaskRuntimeProjectionCanonicalizerMutations.recordIdChange(raw, canonical, "$fieldPath[$index]", records)
      canonical
    }
  }

  fun canonicalizeCompactSummary(
    value: Any?,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    fieldPath: String,
  ): Any? {
    val raw = value as? String ?: return value
    val afterTabs = raw.replace(FEATURE_TASK_RUNTIME_TAB_RUN, " ")
    val afterBackticks = afterTabs.replace("`", "")
    val trimmed = afterBackticks.trim()
    val transforms = buildList {
      if (afterTabs != raw) add(FeatureTaskRuntimeProjectionCanonicalizationTransform.TABS_TO_SPACE)
      if (afterBackticks != afterTabs) add(FeatureTaskRuntimeProjectionCanonicalizationTransform.BACKTICKS_STRIPPED)
      if (trimmed != afterBackticks) add(FeatureTaskRuntimeProjectionCanonicalizationTransform.TRIMMED)
    }
    if (transforms.isNotEmpty()) {
      records += FeatureTaskRuntimeProjectionCanonicalizerMutations.textFreeRecord(fieldPath, transforms)
    }
    return trimmed
  }

  fun trimStringList(
    value: Any?,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    fieldPath: String,
  ): Any? {
    val list = value as? List<*> ?: return value
    return list.mapIndexed { index, raw ->
      FeatureTaskRuntimeProjectionCanonicalizerMutations.trimNonBlankValue(raw, records, "$fieldPath[$index]")
    }
  }
}

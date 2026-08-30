package skillbill.workflow.taskruntime.model

/**
 * SKILL-140 subtask 2: deterministic canonicalization of an agent-produced planning-projection wire
 * map, applied inside [featureTaskRuntimePlanningProjectionFromEnvelope] immediately before strict
 * schema validation. It absorbs lexical trivia — id casing/separators, tab and backtick noise in
 * compact summaries, and surrounding whitespace on nonBlank strings — so the bounded fix loop's
 * attempts are spent on structural problems, not spelling.
 *
 * It never synthesizes missing fields, reorders or drops collection entries, or coerces types: a value
 * of an unexpected shape is passed through untouched so the schema and the typed models reject it
 * exactly as before.
 *
 * One governed exception: every fully-enumerated closed object listed in
 * [FEATURE_TASK_RUNTIME_CLOSED_PROJECTION_OBJECT_KEYS] — the four top-level variants as well as the nested
 * ones — has its unknown keys discarded before strict validation, so an extra-key-only rejection does not
 * consume a fix-loop attempt on a key that carries no governed meaning for any contract. The discard is
 * safe at the top level because each variant is `additionalProperties: false`, so an undeclared key could
 * never have reached a consumer anyway, and because the foreign-owned co-residents another contract
 * requires on the same output — `_goal_planning_shared_context`, `reconciled_state`, `repair_item_results`,
 * `deferred_repair_item_ids`, `unresolvable_repair` — are declared properties of their variants and are
 * therefore retained, as are `projection_kind` and `contract_version`. The standing invariants hold across
 * the discard: no field is synthesized, no type is coerced, and no governed field is dropped.
 *
 * Anti-paste rejection stays with the schema — canonicalization strips backticks and
 * collapses tab runs but never removes an interior line break, so a multi-line paste keeps the CR/LF the
 * `compactSummary` no-line-break guard refuses and any `^`-anchored diff marker stays at its line start
 * where the anti-paste pattern still catches it, and a pasted JSON/diff body still rejects.
 *
 * Because it lives inside the single shared parse function, the producer gate (subtask 1) and the
 * consumer launch seam observe identical behavior with no per-seam copy.
 */
internal object FeatureTaskRuntimeProjectionCanonicalizer {
  /**
   * Canonicalizes [produced] and returns the rewritten map alongside a bounded, text-free diagnostic
   * of what changed. Referential integrity holds: an id map is built from the declared task-id
   * positions first, then applied to every reference position, so a declaration and its references
   * canonicalize to the same value even though the reference never sees the declaration.
   */
  fun canonicalize(produced: Map<String, Any?>): FeatureTaskRuntimeProjectionCanonicalization {
    val records = mutableListOf<FeatureTaskRuntimeProjectionCanonicalizationRecord>()
    val governed = discardUnknownTopLevelKeys(produced, records)
    val declaredIds = buildDeclaredIdMap(governed)
    val canonical = LinkedHashMap<String, Any?>(governed.size)
    governed.forEach { (key, value) ->
      canonical[key] = canonicalizeTopLevel(key, value, declaredIds, records)
    }
    return FeatureTaskRuntimeProjectionCanonicalization(
      canonical = canonical,
      diagnostics = records.take(MAX_CANONICALIZATION_RECORDS),
    )
  }

  // The variant to prune against is chosen by the observed `projection_kind`, which the parse gate has
  // already matched against the consuming declaration. An absent or unrecognized kind prunes nothing:
  // the schema's oneOf rejects it, and guessing a variant there could delete a declared field.
  private fun discardUnknownTopLevelKeys(
    produced: Map<String, Any?>,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
  ): Map<String, Any?> {
    val kind = produced["projection_kind"] as? String ?: return produced
    val governedKeys = FEATURE_TASK_RUNTIME_CLOSED_PROJECTION_OBJECT_KEYS[kind] ?: return produced
    return FeatureTaskRuntimeProjectionCanonicalizerMutations.discardUnknownKeys(produced, governedKeys, kind, records)
  }

  // Declared ids are the tasks[].task_id and task_commitments[].task_id positions; references
  // (depends_on, completed_task_ids) resolve through this map so they can never diverge from the
  // declaration they name. A reference to an id that was not declared falls back to a direct
  // canonicalization, which — being pure — yields the same value it would have as a declaration.
  private fun buildDeclaredIdMap(produced: Map<String, Any?>): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    fun harvest(listKey: String) {
      (produced[listKey] as? List<*>)?.forEach { entry ->
        val id = (entry as? Map<*, *>)?.get("task_id") as? String ?: return@forEach
        map.putIfAbsent(id, FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId(id))
      }
    }
    harvest("tasks")
    harvest("task_commitments")
    return map
  }

  private fun canonicalizeTopLevel(
    key: String,
    value: Any?,
    declaredIds: Map<String, String>,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
  ): Any? = FeatureTaskRuntimeProjectionCanonicalizerTopLevel.canonicalizeTopLevelKey(
    key,
    value,
    declaredIds,
    records,
  )

  /**
   * Task-id rule: trim, lowercase, replace underscore/whitespace runs with a single hyphen, strip
   * characters outside `[a-z0-9-]`, collapse repeated hyphens. A value that reduces to empty (or still
   * fails the schema `taskId` pattern) is left to reject at the schema gate — canonicalization never
   * fabricates a valid id.
   */
  fun canonicalizeTaskId(raw: String): String = raw.trim()
    .lowercase()
    .replace(FEATURE_TASK_RUNTIME_ID_SEPARATOR_RUN, "-")
    .replace(FEATURE_TASK_RUNTIME_ID_INVALID_CHAR, "")
    .replace(FEATURE_TASK_RUNTIME_ID_HYPHEN_RUN, "-")
}

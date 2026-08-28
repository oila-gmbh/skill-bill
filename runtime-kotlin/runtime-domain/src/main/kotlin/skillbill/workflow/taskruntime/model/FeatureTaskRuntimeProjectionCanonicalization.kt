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
@Suppress("TooManyFunctions") // one small leaf helper per schema-declared field class; splitting hurts locality
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
    return discardUnknownKeys(produced, governedKeys, kind, records)
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
        map.putIfAbsent(id, canonicalizeTaskId(id))
      }
    }
    harvest("tasks")
    harvest("task_commitments")
    return map
  }

  @Suppress("CyclomaticComplexMethod") // one arm per schema-declared field path; each is a leaf rule
  private fun canonicalizeTopLevel(
    key: String,
    value: Any?,
    declaredIds: Map<String, String>,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
  ): Any? = when (key) {
    "tasks" -> mapEntries(value) { index, entry -> canonicalizeTaskEntry(entry, declaredIds, records, index) }
    "task_commitments" ->
      mapEntries(value) { index, entry -> canonicalizeCommitmentEntry(entry, records, index) }
    "deviations" -> mapEntries(value) { index, entry -> canonicalizeDeviationEntry(entry, records, index) }
    "completed_task_ids" -> canonicalizeReferenceIds(value, declaredIds, records, key)
    "tests_executed" -> discardUnknownKeysInEntries(value, TEST_EXECUTION_KEYS, key, records)
    "reconciliation_evidence" -> mapObject(promotedReconciliationEvidence(value, records)) {
      trimNonBlank(
        // Adoption runs BEFORE the discard: afterwards the misnamed key is already gone, and all the
        // schema can report is the absence the discard itself created.
        discardUnknownKeys(
          adoptedProseKey(it, RECONCILIATION_EVIDENCE_KEYS, "evidence", key, records),
          RECONCILIATION_EVIDENCE_KEYS,
          key,
          records,
        ),
        "evidence",
        records,
        "reconciliation_evidence.evidence",
      )
    }
    "repository_checkpoint" -> mapObject(value) { checkpoint ->
      trimNonBlank(
        trimNonBlank(
          trimNonBlank(
            discardUnknownKeys(checkpoint, REPOSITORY_CHECKPOINT_KEYS, key, records),
            "fingerprint",
            records,
            "repository_checkpoint.fingerprint",
          ),
          "base_ref",
          records,
          "repository_checkpoint.base_ref",
        ),
        "head_ref",
        records,
        "repository_checkpoint.head_ref",
      )
    }
    in NONBLANK_STRING_LIST_KEYS -> trimStringList(value, records, key)
    else -> value
  }

  private fun canonicalizeTaskEntry(
    entry: Map<String, Any?>,
    declaredIds: Map<String, String>,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    index: Int,
  ): Map<String, Any?> {
    val governed = discardUnknownKeys(entry, PLAN_TASK_KEYS, "tasks[$index]", records)
    val result = LinkedHashMap<String, Any?>(governed.size)
    governed.forEach { (key, value) ->
      result[key] = when (key) {
        "task_id" -> canonicalizeDeclaredId(value, records, "tasks[$index].task_id")
        "depends_on" -> canonicalizeReferenceIds(value, declaredIds, records, "tasks[$index].depends_on")
        "description" -> canonicalizeCompactSummary(value, records, "tasks[$index].description")
        in NONBLANK_STRING_LIST_KEYS -> trimStringList(value, records, "tasks[$index].$key")
        else -> value
      }
    }
    return result
  }

  private fun canonicalizeCommitmentEntry(
    entry: Map<String, Any?>,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    index: Int,
  ): Map<String, Any?> {
    val governed = discardUnknownKeys(entry, TASK_COMMITMENT_KEYS, "task_commitments[$index]", records)
    val result = LinkedHashMap<String, Any?>(governed.size)
    governed.forEach { (key, value) ->
      result[key] = when (key) {
        "task_id" -> canonicalizeDeclaredId(value, records, "task_commitments[$index].task_id")
        in NONBLANK_STRING_LIST_KEYS -> trimStringList(value, records, "task_commitments[$index].$key")
        else -> value
      }
    }
    return result
  }

  private fun canonicalizeDeviationEntry(
    entry: Map<String, Any?>,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    index: Int,
  ): Map<String, Any?> {
    val governed = discardUnknownKeys(entry, DEVIATION_KEYS, "deviations[$index]", records)
    val result = LinkedHashMap<String, Any?>(governed.size)
    governed.forEach { (key, value) ->
      result[key] = when (key) {
        "ref" -> trimNonBlankValue(value, records, "deviations[$index].ref")
        "note" -> canonicalizeCompactSummary(value, records, "deviations[$index].note")
        else -> value
      }
    }
    return result
  }

  // A declaration position: canonicalize directly. The declared-id map is built from the same rule, so
  // the value here equals what any reference to it resolves to.
  private fun canonicalizeDeclaredId(
    value: Any?,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    fieldPath: String,
  ): Any? {
    val raw = value as? String ?: return value
    val canonical = canonicalizeTaskId(raw)
    recordIdChange(raw, canonical, fieldPath, records)
    return canonical
  }

  private fun canonicalizeReferenceIds(
    value: Any?,
    declaredIds: Map<String, String>,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    fieldPath: String,
  ): Any? {
    val list = value as? List<*> ?: return value
    return list.mapIndexed { index, raw ->
      if (raw !is String) return@mapIndexed raw
      val canonical = declaredIds[raw] ?: canonicalizeTaskId(raw)
      recordIdChange(raw, canonical, "$fieldPath[$index]", records)
      canonical
    }
  }

  // Collapses tab runs and strips backticks — the trivia AC-002 accepts — but never removes an interior
  // line break. A line break is the multi-line-paste signal the schema's `^[^\n\r\t`]+$` guard keys on;
  // collapsing it would flatten a multi-line JSON/diff body into an accepted single line and slide a
  // `^`-anchored diff marker off its line start where the anti-paste pattern misses it. Multi-line
  // content is left intact so the schema still rejects it. The trailing trim removes only boundary
  // whitespace, so a leading-line diff marker lands at position 0 where the pattern still catches it.
  private fun canonicalizeCompactSummary(
    value: Any?,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    fieldPath: String,
  ): Any? {
    val raw = value as? String ?: return value
    val afterTabs = raw.replace(TAB_RUN, " ")
    val afterBackticks = afterTabs.replace("`", "")
    val trimmed = afterBackticks.trim()
    val transforms = buildList {
      if (afterTabs != raw) add(FeatureTaskRuntimeProjectionCanonicalizationTransform.TABS_TO_SPACE)
      if (afterBackticks != afterTabs) add(FeatureTaskRuntimeProjectionCanonicalizationTransform.BACKTICKS_STRIPPED)
      if (trimmed != afterBackticks) add(FeatureTaskRuntimeProjectionCanonicalizationTransform.TRIMMED)
    }
    if (transforms.isNotEmpty()) records += textFreeRecord(fieldPath, transforms)
    return trimmed
  }

  private fun trimStringList(
    value: Any?,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    fieldPath: String,
  ): Any? {
    val list = value as? List<*> ?: return value
    return list.mapIndexed { index, raw -> trimNonBlankValue(raw, records, "$fieldPath[$index]") }
  }

  // Scoped to fully-enumerated closed objects, top-level variants included. An unknown key there carries
  // no governed meaning for any contract and could not have survived `additionalProperties: false`, so
  // discarding it costs nothing and spares the fix loop an attempt. The discarded key's value is never
  // recorded.
  private fun discardUnknownKeys(
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

  /**
   * Adopts a misnamed key's prose under the field that was meant to hold it.
   *
   * Without this the two halves of the closed-object contract defeat each other: the discard strips
   * an unknown key, and the schema then rejects the object for the required field that key WAS. That
   * is how `{"reconciled": true, "notes": "<the evidence>"}` became "required property 'evidence' not
   * found" — the runtime deleted in-cap, correct prose and then reported it missing. The producer
   * cannot learn from that rejection either, because from its side it did supply the text.
   *
   * Deliberately refuses to guess. It fires only when the prose field is absent, exactly one unknown
   * key exists, and that key holds non-blank text; two unknown keys are an ambiguity no rename should
   * resolve. It is also driven per call site rather than generally, because a general
   * "single unknown key fills the single missing required field" rule would happily move prose into
   * an identifier — `deviation` requires `ref` and `note`, and misfiling a sentence as `ref` is worse
   * than rejecting.
   *
   * Over-length prose is adopted as-is and left to fail on length. That reports the real problem, and
   * a length violation is the one class with a correction that tells the producer to compress.
   */
  private fun adoptedProseKey(
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

  @Suppress("UNCHECKED_CAST")
  private fun discardUnknownKeysInEntries(
    value: Any?,
    governedKeys: Set<String>,
    fieldPath: String,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
  ): Any? {
    val list = value as? List<*> ?: return value
    return list.mapIndexed { index, entry ->
      val stringKeyed = (entry as? Map<*, *>)?.takeIf { map -> map.keys.all { it is String } }
        ?: return@mapIndexed entry
      discardUnknownKeys(stringKeyed as Map<String, Any?>, governedKeys, "$fieldPath[$index]", records)
    }
  }

  /**
   * A bare string at `reconciliation_evidence` becomes the object the schema declares.
   *
   * `reconciled` is pinned to `const: true` on the receipt variant, so it carries no information the
   * contract had not already fixed, and the only field left to fill is `evidence` — which is exactly
   * what the producer wrote. Promoting is therefore lossless, unlike rejecting a completed receipt
   * whose evidence sweep is already on the wire.
   *
   * A blank string promotes nothing: `evidence` is `nonBlank`, so the promotion would only trade a
   * type error for a value error while inventing a `reconciled: true` claim the producer never made.
   */
  private fun promotedReconciliationEvidence(
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

  private fun trimNonBlank(
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

  private fun trimNonBlankValue(
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

  private fun recordIdChange(
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

  private fun textFreeRecord(
    fieldPath: String,
    transforms: List<FeatureTaskRuntimeProjectionCanonicalizationTransform>,
  ) = FeatureTaskRuntimeProjectionCanonicalizationRecord(fieldPath = fieldPath, transforms = transforms)

  @Suppress("UNCHECKED_CAST")
  private inline fun mapEntries(value: Any?, transform: (Int, Map<String, Any?>) -> Map<String, Any?>): Any? {
    val list = value as? List<*> ?: return value
    return list.mapIndexed { index, entry ->
      val entryMap = entry as? Map<*, *> ?: return@mapIndexed entry
      transform(index, entryMap.stringKeyedView())
    }
  }

  @Suppress("UNCHECKED_CAST")
  private inline fun mapObject(value: Any?, transform: (Map<String, Any?>) -> Map<String, Any?>): Any? {
    val map = value as? Map<*, *> ?: return value
    return transform(map.stringKeyedView())
  }

  // Reads the wire object as a string-keyed map without coercing entries; a non-string key would violate
  // the wire contract and is left for the schema to reject. The unknown-key discard applies only to a
  // string-keyed view, so a non-string-keyed map is never pruned.
  @Suppress("UNCHECKED_CAST")
  private fun Map<*, *>.stringKeyedView(): Map<String, Any?> =
    if (keys.all { it is String }) this as Map<String, Any?> else LinkedHashMap()

  private val TAB_RUN = Regex("\\t+")

  private val ID_SEPARATOR_RUN = Regex("[\\s_]+")
  private val ID_INVALID_CHAR = Regex("[^a-z0-9-]")
  private val ID_HYPHEN_RUN = Regex("-{2,}")

  // Top-level and per-task/per-commitment nonBlank string-array fields whose items are trimmed.
  // criterion_refs is a pattern-typed field, not nonBlank, so it stays out of scope and any lexical
  // trivia there is left for the schema to reject.
  private val NONBLANK_STRING_LIST_KEYS = setOf(
    "affected_boundaries",
    "patterns_and_decisions",
    "risks",
    "validation_strategy",
    "unresolved_questions",
    "evidence_refs",
    "unresolved_items",
    "target_paths_or_symbols",
    "test_obligations",
    "constraints",
  )

  private val RECONCILIATION_EVIDENCE_KEYS = setOf("reconciled", "evidence")
  private val REPOSITORY_CHECKPOINT_KEYS =
    setOf("fingerprint", "base_ref", "head_ref", "working_tree_owned_paths")
  private val PLAN_TASK_KEYS = setOf(
    "task_id",
    "depends_on",
    "description",
    "criterion_refs",
    "target_paths_or_symbols",
    "test_obligations",
    "constraints",
  )
  private val TASK_COMMITMENT_KEYS = setOf("task_id", "criterion_refs", "test_obligations", "constraints")
  private val TEST_EXECUTION_KEYS = setOf("name", "outcome")
  private val DEVIATION_KEYS = setOf("ref", "note")

  /**
   * Task-id rule: trim, lowercase, replace underscore/whitespace runs with a single hyphen, strip
   * characters outside `[a-z0-9-]`, collapse repeated hyphens. A value that reduces to empty (or still
   * fails the schema `taskId` pattern) is left to reject at the schema gate — canonicalization never
   * fabricates a valid id.
   */
  fun canonicalizeTaskId(raw: String): String = raw.trim()
    .lowercase()
    .replace(ID_SEPARATOR_RUN, "-")
    .replace(ID_INVALID_CHAR, "")
    .replace(ID_HYPHEN_RUN, "-")
}

/** Result of [FeatureTaskRuntimeProjectionCanonicalizer.canonicalize]: the rewritten wire map plus a
 *  bounded, text-free record of what changed. */
internal data class FeatureTaskRuntimeProjectionCanonicalization(
  val canonical: Map<String, Any?>,
  val diagnostics: List<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
)

/**
 * One applied canonicalization, bounded and carrying no plan body or prompt text (AC-006). Identifier
 * fields record their original and canonical values because both are bounded lexical ids; compact-summary
 * and nonBlank fields record only the field path and the applied transform kinds, never the field text.
 * The diagnostics are returned for later telemetry pickup and must never flow into prompt context.
 */
data class FeatureTaskRuntimeProjectionCanonicalizationRecord(
  val fieldPath: String,
  val transforms: List<FeatureTaskRuntimeProjectionCanonicalizationTransform>,
  val originalId: String? = null,
  val canonicalId: String? = null,
)

enum class FeatureTaskRuntimeProjectionCanonicalizationTransform(val wireValue: String) {
  TASK_ID_NORMALIZED("task_id_normalized"),
  TABS_TO_SPACE("tabs_to_space"),
  BACKTICKS_STRIPPED("backticks_stripped"),
  TRIMMED("trimmed"),
  UNKNOWN_KEY_DISCARDED("unknown_key_discarded"),

  /** A scalar rewritten as the single-meaningful-field object its declared shape expects. */
  SCALAR_PROMOTED_TO_OBJECT("scalar_promoted_to_object"),

  /** A lone unknown key's prose adopted under the absent required field it was written for. */
  MISNAMED_KEY_ADOPTED("misnamed_key_adopted"),
}

internal val FEATURE_TASK_RUNTIME_CLOSED_PROJECTION_OBJECT_KEYS: Map<String, Set<String>> = emptyMap()

/** The count cap on recorded canonicalizations, so diagnostics stay bounded regardless of projection
 *  size. */
const val MAX_CANONICALIZATION_RECORDS: Int = 256

/** The length cap on a recorded id value; the schema already bounds a valid `taskId` to 128 chars, and a
 *  pre-canonical original is truncated to the same bound. */
const val MAX_RECORDED_ID_LENGTH: Int = 128

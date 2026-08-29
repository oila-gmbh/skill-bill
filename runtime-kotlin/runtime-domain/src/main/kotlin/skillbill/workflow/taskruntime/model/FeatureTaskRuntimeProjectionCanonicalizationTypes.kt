package skillbill.workflow.taskruntime.model

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
internal data class FeatureTaskRuntimeProjectionCanonicalizationRecord(
  val fieldPath: String,
  val transforms: List<FeatureTaskRuntimeProjectionCanonicalizationTransform>,
  val originalId: String? = null,
  val canonicalId: String? = null,
)

internal enum class FeatureTaskRuntimeProjectionCanonicalizationTransform(val wireValue: String) {
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

package skillbill.desktop.core.domain.model

/**
 * Localization-agnostic descriptor of a scaffold-form validation failure. The domain layer emits
 * these instead of pre-rendered English so it stays free of any string-resource dependency; the UI
 * maps each [id] to a string resource and formats it with [args]. Baseline-layer failures carry the
 * 1-based layer number as the first arg.
 */
data class ScaffoldValidationMessage(
  val id: ScaffoldValidationId,
  val args: List<String> = emptyList(),
)

enum class ScaffoldValidationId {
  SKILL_NAME_REQUIRED,
  PLATFORM_SLUG_REQUIRED,
  PLATFORM_REQUIRED,
  FAMILY_REQUIRED,
  CODE_REVIEW_AREA_REQUIRED,
  ADD_ON_NAME_REQUIRED,
  OWNING_PLATFORM_PACK_REQUIRED,
  BASELINE_PACK_REQUIRED,
  BASELINE_PACK_UNAVAILABLE,
  BASELINE_SKILL_REQUIRED,
  BASELINE_SKILL_UNAVAILABLE,
  BASELINE_MODE_UNSUPPORTED,
  BASELINE_SCOPE_UNSUPPORTED,
  DUPLICATE_BASELINE_LAYER,
  BASELINE_SELF_REFERENCE,
  BASELINE_COMPOSITION_CYCLE,
}

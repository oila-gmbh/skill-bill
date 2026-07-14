package skillbill.config.model

import skillbill.install.model.InstallAgent
import skillbill.review.context.ReviewContextBudgetPolicy

/** Spec source mode: `local` reads specs from the working tree, `linear` sources them from Linear. */
enum class SpecType(
  val id: String,
) {
  LOCAL("local"),
  LINEAR("linear"),
  ;

  companion object {
    val supportedIds: List<String> = entries.map(SpecType::id)
  }
}

/**
 * Recognized repo-local config keys and their built-in defaults. Adding a future key
 * touches only this enum and a matching accessor on [RepoLocalConfig]; unknown keys are
 * ignored by the adapter, so unrelated callers never change.
 */
enum class RepoLocalConfigKey(
  val key: String,
  val builtinDefault: String,
) {
  SPEC_TYPE("spec_type", SpecType.LOCAL.id),
  CODE_REVIEW_PARALLEL_AGENT("code_review_parallel_agent", RepoLocalConfig.NO_PARALLEL_AGENT),
  ;

  companion object {
    val knownKeys: Set<String> = entries.map(RepoLocalConfigKey::key).toSet()
  }
}

/**
 * Typed view of the repo-local config. No raw pass-through map is exposed: the codebase
 * forbids raw `Map<String, Any?>` public shapes and no consumer needs one, so each known
 * key gets a validated typed accessor instead.
 */
data class RepoLocalConfig(
  val specType: SpecType,
  val codeReviewParallelAgent: String,
  val reviewContextBudget: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy.DEFAULT,
) {
  companion object {
    const val NO_PARALLEL_AGENT: String = "none"

    fun defaults(): RepoLocalConfig = RepoLocalConfig(
      specType = SpecType.LOCAL,
      codeReviewParallelAgent = NO_PARALLEL_AGENT,
      reviewContextBudget = ReviewContextBudgetPolicy.DEFAULT,
    )
  }
}

/** Returns null for any unknown/invalid value so the adapter can raise the typed contract error. */
fun parseSpecType(raw: String?): SpecType? {
  val normalized = raw?.trim()?.lowercase() ?: return null
  return SpecType.entries.firstOrNull { specType -> specType.id == normalized }
}

/**
 * Accepts any supported install-agent id or the `none` sentinel; returns null otherwise so the
 * adapter can raise the typed contract error.
 */
fun parseCodeReviewParallelAgent(raw: String?): String? {
  val normalized = raw?.trim()?.lowercase() ?: return null
  val valid = InstallAgent.supportedIds + RepoLocalConfig.NO_PARALLEL_AGENT
  return normalized.takeIf { value -> value in valid }
}

/** Single precedence helper reused by every caller: `explicit arg > config > built-in default`. */
object RepoLocalConfigResolution {
  fun <T> resolve(explicit: T?, config: T?, builtinDefault: T): T = explicit ?: config ?: builtinDefault
}

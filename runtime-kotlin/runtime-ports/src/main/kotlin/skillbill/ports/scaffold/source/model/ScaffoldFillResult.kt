package skillbill.ports.scaffold.source.model

import skillbill.ports.scaffold.model.ScaffoldSkillStatus

/**
 * SKILL-52.3 subtask 3 — Fully typed result for `ScaffoldGateway.fill(...)`.
 *
 * Reports the post-mutation skill [status] (the shared [ScaffoldSkillStatus] record),
 * the `wrapper_regenerated` bookkeeping flag, and the fill-specific [updatedSection] /
 * [validatorRan] fields. The legacy open-boundary `payload: Map<String, Any?>` and its
 * desync `init {}` guard were retired in SKILL-52.3 subtask 3; the adapter-owned
 * `runtime-cli` mapper rebuilds the byte-equivalent ordered wire map from these typed
 * fields in producer key order (status keys, `wrapper_regenerated`, `updated_section`,
 * `validator_ran`).
 */
data class ScaffoldFillResult(
  val status: ScaffoldSkillStatus,
  val wrapperRegenerated: Boolean,
  val updatedSection: String?,
  val validatorRan: Boolean,
) {
  val skillName: String get() = status.skillName
}

package skillbill.ports.scaffold.source.model

import skillbill.ports.scaffold.model.ScaffoldSkillStatus

/**
 * SKILL-52.3 subtask 3 — Fully typed result for `ScaffoldGateway.saveExactContent(...)`.
 *
 * Same shape as the fill result minus section-targeting: replaces the entire
 * `content.md` body verbatim, so [updatedSection] is always `null`. The legacy
 * open-boundary `payload` map and its desync `init {}` guard were retired in
 * SKILL-52.3 subtask 3; the adapter-owned `runtime-cli` mapper rebuilds the wire map
 * from these typed fields in producer key order (status keys, `wrapper_regenerated`,
 * `updated_section`, `validator_ran`).
 */
data class ScaffoldSaveExactContentResult(
  val status: ScaffoldSkillStatus,
  val wrapperRegenerated: Boolean,
  val validatorRan: Boolean,
) {
  val skillName: String get() = status.skillName
  val updatedSection: String? get() = null
}

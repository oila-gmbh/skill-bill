package skillbill.ports.scaffold.catalog.model

import skillbill.ports.scaffold.model.ScaffoldSkillStatus

/**
 * SKILL-52.3 subtask 3 — Fully typed result for `ScaffoldGateway.show(...)`.
 *
 * Surfaces the shared [ScaffoldSkillStatus] record for a single content-managed skill,
 * including the optional `review_composition`, `content_preview` / `content`, and
 * `issues` tail fields carried on the status record. The legacy open-boundary `payload`
 * map and its desync `init {}` guard were retired in SKILL-52.3 subtask 3; the
 * adapter-owned `runtime-cli` mapper rebuilds the byte-equivalent ordered wire map from
 * [status].
 */
data class ScaffoldShowResult(
  val status: ScaffoldSkillStatus,
) {
  val skillName: String get() = status.skillName
}

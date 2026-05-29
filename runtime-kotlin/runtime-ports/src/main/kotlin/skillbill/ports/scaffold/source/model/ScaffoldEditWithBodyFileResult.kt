package skillbill.ports.scaffold.source.model

import skillbill.ports.scaffold.model.ScaffoldSkillStatus

/**
 * SKILL-52.3 subtask 3 — Fully typed result for `ScaffoldGateway.editWithBodyFile(...)`.
 *
 * Edit-via-body-file emits the editor bookkeeping fields ([usedEditor],
 * [guidedSections], [updatedSection], [validatorRan]) THEN the post-mutation skill
 * [status] and `wrapper_regenerated` flag. The legacy open-boundary `payload` map and
 * its desync `init {}` guard were retired in SKILL-52.3 subtask 3; the adapter-owned
 * `runtime-cli` mapper rebuilds the byte-equivalent ordered wire map from these typed
 * fields in producer key order (`used_editor`, `guided_sections`, `updated_section`,
 * `validator_ran`, then the status keys + `wrapper_regenerated`).
 */
data class ScaffoldEditWithBodyFileResult(
  val usedEditor: Boolean,
  val guidedSections: List<String>,
  val updatedSection: String?,
  val validatorRan: Boolean,
  val status: ScaffoldSkillStatus,
  val wrapperRegenerated: Boolean,
) {
  val skillName: String get() = status.skillName
}

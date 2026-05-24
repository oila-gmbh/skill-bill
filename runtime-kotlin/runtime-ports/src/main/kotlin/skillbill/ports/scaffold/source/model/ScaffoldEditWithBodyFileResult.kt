package skillbill.ports.scaffold.source.model

import skillbill.boundary.OpenBoundaryMap

/**
 * SKILL-52.1 subtask 3 — Typed result for `ScaffoldGateway.editWithBodyFile(...)`.
 *
 * Edit-via-body-file emits the editor bookkeeping fields (used_editor, guided_sections,
 * updated_section, validator_ran) THEN the post-mutation status payload. Legacy
 * insertion order is preserved verbatim via [payload].
 *
 * SKILL-52.1 subtask 3 (F-011): the producer uses `mapOf(...) + mutateContent(...)`
 * (backed by `LinkedHashMap` on JVM), not `linkedMapOf(...)`; the contract is "ordered map".
 */
data class ScaffoldEditWithBodyFileResult(
  val skillName: String,
  val usedEditor: Boolean,
  val validatorRan: Boolean,
  @OpenBoundaryMap("Scaffold editWithBodyFile wire payload (legacy raw-map surface preserved for byte-equivalent JSON)")
  val payload: Map<String, Any?>,
) {
  init {
    // SKILL-52.1 subtask 3 (F-010): typed/payload desync invariants.
    require(payload["skill_name"] == skillName) {
      "ScaffoldEditWithBodyFileResult typed/payload desync: skill_name"
    }
    require(payload["used_editor"] == usedEditor) {
      "ScaffoldEditWithBodyFileResult typed/payload desync: used_editor"
    }
    require(payload["validator_ran"] == validatorRan) {
      "ScaffoldEditWithBodyFileResult typed/payload desync: validator_ran"
    }
  }
}

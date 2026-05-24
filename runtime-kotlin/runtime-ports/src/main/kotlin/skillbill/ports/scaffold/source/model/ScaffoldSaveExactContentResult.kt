package skillbill.ports.scaffold.source.model

import skillbill.boundary.OpenBoundaryMap

/**
 * SKILL-52.1 subtask 3 — Typed result for `ScaffoldGateway.saveExactContent(...)`.
 *
 * Same shape as the fill result minus the section-targeting semantics: replaces the
 * entire `content.md` body verbatim. Legacy insertion order is preserved verbatim
 * via [payload].
 *
 * SKILL-52.1 subtask 3 (F-011): the producer uses `mutateContent(...) + mapOf(...)`
 * (backed by `LinkedHashMap` on JVM), not `linkedMapOf(...)`; the contract is "ordered map".
 */
data class ScaffoldSaveExactContentResult(
  val skillName: String,
  val validatorRan: Boolean,
  @OpenBoundaryMap("Scaffold saveExactContent wire payload (legacy raw-map surface preserved for byte-equivalent JSON)")
  val payload: Map<String, Any?>,
) {
  init {
    // SKILL-52.1 subtask 3 (F-010): typed/payload desync invariants.
    require(payload["skill_name"] == skillName) {
      "ScaffoldSaveExactContentResult typed/payload desync: skill_name"
    }
    require(payload["validator_ran"] == validatorRan) {
      "ScaffoldSaveExactContentResult typed/payload desync: validator_ran"
    }
  }
}

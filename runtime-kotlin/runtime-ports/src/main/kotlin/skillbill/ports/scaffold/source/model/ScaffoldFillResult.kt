package skillbill.ports.scaffold.source.model

import skillbill.boundary.OpenBoundaryMap

/**
 * SKILL-52.1 subtask 3 — Typed result for `ScaffoldGateway.fill(...)`.
 *
 * Reports the post-mutation status payload (skill_name, package, platform, family,
 * area, content_file, render_command, completion_status, section_count, sections,
 * recommended_commands, wrapper_regenerated) merged with the fill bookkeeping fields
 * (updated_section, validator_ran). Legacy insertion order is preserved verbatim
 * via [payload].
 *
 * SKILL-52.1 subtask 3 (F-011): the producer uses `mutateContent(...) + mapOf(...)`
 * (backed by `LinkedHashMap` on JVM), not `linkedMapOf(...)`; the contract is "ordered map".
 */
data class ScaffoldFillResult(
  val skillName: String,
  val validatorRan: Boolean,
  @OpenBoundaryMap("Scaffold fill wire payload (legacy raw-map surface preserved for byte-equivalent JSON)")
  val payload: Map<String, Any?>,
) {
  init {
    // SKILL-52.1 subtask 3 (F-010): typed/payload desync invariants.
    require(payload["skill_name"] == skillName) {
      "ScaffoldFillResult typed/payload desync: skill_name"
    }
    require(payload["validator_ran"] == validatorRan) {
      "ScaffoldFillResult typed/payload desync: validator_ran"
    }
  }
}

package skillbill.ports.scaffold.catalog.model

import skillbill.boundary.OpenBoundaryMap

/**
 * SKILL-52.1 subtask 3 — Typed result for `ScaffoldGateway.show(...)`.
 *
 * Surfaces the status payload for a single content-managed skill (skill_name, package,
 * platform, family, area, content_file, render_command, completion_status,
 * section_count, sections, recommended_commands, optional review_composition,
 * optional content_preview / content, optional issues). The wire payload preserves
 * the legacy insertion order via [payload]; [skillName] is lifted as a stable
 * top-level scalar for direct callers.
 *
 * SKILL-52.1 subtask 3 (F-011): the producer uses `mapOf(...) + mapOf(...)` (backed by
 * `LinkedHashMap` on JVM), not `linkedMapOf(...)`; the contract is "ordered map".
 */
data class ScaffoldShowResult(
  val skillName: String,
  @OpenBoundaryMap("Scaffold show wire payload (legacy raw-map surface preserved for byte-equivalent JSON)")
  val payload: Map<String, Any?>,
) {
  init {
    // SKILL-52.1 subtask 3 (F-010): typed/payload desync invariant.
    require(payload["skill_name"] == skillName) {
      "ScaffoldShowResult typed/payload desync: skill_name"
    }
  }
}

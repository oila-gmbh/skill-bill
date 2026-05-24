package skillbill.ports.scaffold.repo.model

import skillbill.boundary.OpenBoundaryMap

/**
 * SKILL-52.1 subtask 3 — Typed result for `ScaffoldGateway.validate(...)`.
 *
 * Reports validator status (`pass` / `fail`) plus collected issues. Two wire-shape
 * branches are flattened into one typed model: the no-skill ("repo" mode) payload
 * and the selected-skill payload. The legacy insertion order is preserved verbatim
 * via [payload]; [status] is lifted so CLI exit-code selection can branch on it
 * without re-reading the open-boundary map.
 *
 * SKILL-52.1 subtask 3 (F-011): the producer uses `mapOf(...)` (backed by
 * `LinkedHashMap` on JVM), not `linkedMapOf(...)`; the contract is "ordered map".
 */
data class ScaffoldValidateResult(
  val status: String,
  @OpenBoundaryMap("Scaffold validate wire payload (legacy raw-map surface preserved for byte-equivalent JSON)")
  val payload: Map<String, Any?>,
) {
  init {
    // SKILL-52.1 subtask 3 (F-010): typed/payload desync invariant.
    require(payload["status"] == status) {
      "ScaffoldValidateResult typed/payload desync: status"
    }
  }
}

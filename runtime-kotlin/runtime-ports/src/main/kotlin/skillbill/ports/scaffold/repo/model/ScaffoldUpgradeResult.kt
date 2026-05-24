package skillbill.ports.scaffold.repo.model

import skillbill.boundary.OpenBoundaryMap

/**
 * SKILL-52.1 subtask 3 — Typed result for `ScaffoldGateway.upgrade(...)`.
 *
 * Reports how many wrapper / native-agent artifacts were regenerated under the repo
 * and whether the validator ran. Legacy raw-map insertion order is preserved by
 * adapter mappers via [payload].
 *
 * SKILL-52.1 subtask 3 (F-011): the producer uses `mapOf(...)` (backed by
 * `LinkedHashMap` on JVM), not `linkedMapOf(...)`; the contract is "ordered map".
 */
data class ScaffoldUpgradeResult(
  val regeneratedCount: Int,
  val validatorRan: Boolean,
  @OpenBoundaryMap("Scaffold upgrade wire payload (legacy raw-map surface preserved for byte-equivalent JSON)")
  val payload: Map<String, Any?>,
) {
  init {
    // SKILL-52.1 subtask 3 (F-010): typed/payload desync invariants.
    require((payload["regenerated_count"] as? Number)?.toInt() == regeneratedCount) {
      "ScaffoldUpgradeResult typed/payload desync: regenerated_count"
    }
    require(payload["validator_ran"] == validatorRan) {
      "ScaffoldUpgradeResult typed/payload desync: validator_ran"
    }
  }
}

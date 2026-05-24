package skillbill.ports.scaffold.catalog.model

import skillbill.boundary.OpenBoundaryMap

/**
 * SKILL-52.1 subtask 3 — Typed result for `ScaffoldGateway.explain(...)`.
 *
 * Explanation of the governed authoring boundary plus the CLI workflow. When a
 * skill name is supplied the legacy payload also carries a nested `skill` map with
 * concrete paths and recommended commands. The wire shape is preserved verbatim in
 * [payload].
 *
 * SKILL-52.1 subtask 3 (F-011): the producer uses `mutableMapOf(...)` (backed by
 * `LinkedHashMap` on JVM), not `linkedMapOf(...)`; the contract is "ordered map".
 *
 * Note (F-010): no lifted scalars to invariant-check — the model is payload-only.
 */
data class ScaffoldExplainResult(
  @OpenBoundaryMap("Scaffold explain wire payload (legacy raw-map surface preserved for byte-equivalent JSON)")
  val payload: Map<String, Any?>,
)

package skillbill.ports.scaffold.catalog.model

import skillbill.boundary.OpenBoundaryMap

/**
 * SKILL-52.1 subtask 3 — Typed result for `ScaffoldGateway.list(...)`.
 *
 * The list operation enumerates content-managed skills under a repo and reports their
 * authoring status. The wire shape is preserved via the [payload] open-boundary map so
 * CLI/MCP adapter mappers can emit byte-equivalent JSON. The strongly-typed [repoRoot]
 * and [skillCount] fields lift the two stable top-level scalars so callers and tests
 * can inspect them without re-parsing the open-boundary map.
 *
 * SKILL-52.1 subtask 3 (F-011): the legacy raw-map producer in
 * `AuthoringOperations.list(...)` returns `mapOf("repo_root" to ..., "skill_count" to ...,
 * "skills" to ...)` which is backed by `LinkedHashMap` on the JVM. The contract is
 * "ordered map (LinkedHashMap-backed by stdlib)", not the `linkedMapOf` constructor; that
 * insertion order MUST be preserved by the adapter mappers when reconstructing the wire
 * payload from this typed model.
 */
data class ScaffoldListResult(
  val repoRoot: String,
  val skillCount: Int,
  @OpenBoundaryMap("Scaffold list wire payload (legacy raw-map surface preserved for byte-equivalent JSON)")
  val payload: Map<String, Any?>,
) {
  init {
    // SKILL-52.1 subtask 3 (F-010): guard against typed/payload desync. The typed scalars
    // MUST equal the lifted payload entries; otherwise a producer/gateway mismatch could
    // silently mask wire-shape drift. Number lifts use widening compare (Long after JSON
    // round-trip would still equal the typed Int).
    require(payload["repo_root"] == repoRoot) {
      "ScaffoldListResult typed/payload desync: repo_root"
    }
    require((payload["skill_count"] as? Number)?.toInt() == skillCount) {
      "ScaffoldListResult typed/payload desync: skill_count"
    }
  }
}

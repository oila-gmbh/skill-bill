package skillbill.ports.scaffold.catalog.model

import skillbill.ports.scaffold.model.ScaffoldSkillStatus

/**
 * SKILL-52.3 subtask 3 — Fully typed result for `ScaffoldGateway.list(...)`.
 *
 * The list operation enumerates content-managed skills under a repo and reports their
 * authoring status. Every field is strongly typed; the legacy open-boundary
 * `payload: Map<String, Any?>` and its desync `init {}` guard were retired in
 * SKILL-52.3 subtask 3. Adapter-owned wire mappers (`runtime-cli` /
 * `runtime-desktop`) rebuild the byte-equivalent ordered wire map from [repoRoot],
 * [skillCount], and [skills] in the producer key order (`repo_root`, `skill_count`,
 * `skills`).
 */
data class ScaffoldListResult(
  val repoRoot: String,
  val skillCount: Int,
  val skills: List<ScaffoldSkillStatus>,
)

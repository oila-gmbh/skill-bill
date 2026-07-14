package skillbill.ports.scaffold.model

/**
 * SKILL-52.3 subtask 3 — Reusable typed status record shared by the scaffold
 * catalog/source result models (`ScaffoldListResult`, `ScaffoldShowResult`,
 * `ScaffoldFillResult`, `ScaffoldSaveExactContentResult`,
 * `ScaffoldEditWithBodyFileResult`).
 *
 * Replaces the legacy `statusPayload(...)` open-boundary `Map<String, Any?>` that
 * SKILL-52.1 subtask 3 carried verbatim through each result's `payload` field. The
 * adapter-owned wire mappers (`runtime-cli` / `runtime-desktop`) rebuild the legacy
 * ordered wire map from these typed fields in the exact producer key order
 * (`skill_name`, `package`, `platform`, `family`, `area`, `content_file`,
 * `render_command`, `completion_status`, `section_count`, `sections`,
 * `recommended_commands`). [reviewComposition], [contentPreview], [content], and
 * [issues] are optional tail keys appended only when the producer emitted them.
 */
data class ScaffoldSkillStatus(
  val skillName: String,
  val packageName: String,
  val platform: String,
  val family: String,
  val area: String,
  val contentFile: String,
  val renderCommand: String,
  val completionStatus: String,
  val sectionCount: Int,
  val sections: List<ScaffoldSectionStatus>,
  val recommendedCommands: List<String>,
  val reviewComposition: ScaffoldReviewComposition? = null,
  val contentPreview: String? = null,
  val content: String? = null,
  val issues: List<String>? = null,
  val category: String = "skill",
  val slug: String? = null,
  val description: String? = null,
  val supportedAgents: List<String> = emptyList(),
  val consumers: List<String> = emptyList(),
  val manifestFile: String? = null,
)

/**
 * SKILL-52.3 subtask 3 — Typed per-section status entry (legacy keys: `heading`,
 * `status`, `line_count`, `preview`).
 */
data class ScaffoldSectionStatus(
  val heading: String,
  val status: String,
  val lineCount: Int,
  val preview: String,
)

/**
 * SKILL-52.3 subtask 3 — Typed review-composition block (legacy keys: `source`,
 * `summary`, `baseline_layers`) surfaced by the manifest-declared baseline review
 * catalog when present.
 */
data class ScaffoldReviewComposition(
  val source: String,
  val summary: String,
  val baselineLayers: List<ScaffoldBaselineLayer>,
)

/**
 * SKILL-52.3 subtask 3 — Typed baseline-layer entry (legacy keys: `platform`,
 * `skill`, `scope`, `required`, `mode`).
 */
data class ScaffoldBaselineLayer(
  val platform: String,
  val skill: String,
  val scope: String,
  val required: Boolean,
  val mode: String,
)

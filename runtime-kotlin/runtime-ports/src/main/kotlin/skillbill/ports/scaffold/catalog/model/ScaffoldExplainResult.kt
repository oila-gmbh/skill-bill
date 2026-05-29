package skillbill.ports.scaffold.catalog.model

/**
 * SKILL-52.3 subtask 3 — Fully typed result for `ScaffoldGateway.explain(...)`.
 *
 * Explanation of the governed authoring boundary plus the CLI workflow. When a skill
 * name is supplied the optional [skill] block carries concrete paths and recommended
 * commands. The legacy open-boundary `payload` map was retired in SKILL-52.3 subtask 3;
 * the adapter-owned `runtime-cli` mapper rebuilds the byte-equivalent ordered wire map
 * from these typed fields in producer key order (`explanation`, `editable_surface`,
 * `generated_surface`, `governed_sidecars`, `normal_workflow`, `notes`, then optional
 * `skill`).
 */
data class ScaffoldExplainResult(
  val explanation: String,
  val editableSurface: List<String>,
  val generatedSurface: List<String>,
  val governedSidecars: List<String>,
  val normalWorkflow: List<String>,
  val notes: List<String>,
  val skill: ScaffoldExplainSkill? = null,
)

/**
 * SKILL-52.3 subtask 3 — Typed nested `skill` block on [ScaffoldExplainResult] (legacy
 * keys: `skill_name`, `content_file`, `render_command`, `recommended_commands`).
 */
data class ScaffoldExplainSkill(
  val skillName: String,
  val contentFile: String,
  val renderCommand: String,
  val recommendedCommands: List<String>,
)

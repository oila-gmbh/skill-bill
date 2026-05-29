package skillbill.ports.scaffold.repo.model

/**
 * SKILL-52.3 subtask 3 — Fully typed result for `ScaffoldGateway.validate(...)`.
 *
 * Reports validator status (`pass` / `fail`) plus collected [issues]. Two wire-shape
 * branches are flattened into one typed model: the no-skill ("repo" [mode]) result
 * (`repo_root`, `mode`, `status`, `issues`) and the [mode] = "selected" result
 * (`repo_root`, `mode`, `skill_names`, `status`, `issues`, `suggested_commands`).
 * [skillNames] and [suggestedCommands] are populated only in selected mode. The legacy
 * open-boundary `payload` map and its desync `init {}` guard were retired in SKILL-52.3
 * subtask 3; the adapter-owned `runtime-cli` mapper rebuilds the byte-equivalent
 * ordered wire map per mode, and the desktop `ValidationSummaryMapper` consumes
 * [status] and [issues] directly.
 */
data class ScaffoldValidateResult(
  val repoRoot: String,
  val mode: String,
  val status: String,
  val issues: List<String>,
  val skillNames: List<String>? = null,
  val suggestedCommands: List<String>? = null,
)

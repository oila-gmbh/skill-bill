package skillbill.scaffold

internal fun qualityCheckContent(summary: String): String = buildString {
  appendLine("## Purpose")
  appendLine()
  appendLine(summary)
  appendLine()
  appendLine("## Execution Steps")
  appendLine()
  appendLine("1. Determine the files in scope for the current unit of work.")
  appendLine("2. Run the platform's quality-check entrypoint and capture the failures.")
  appendLine("3. Fix only the failures that belong to the scoped work unless the contract says otherwise.")
  appendLine("4. Re-run the quality check until the scoped failures are resolved.")
  appendLine()
  appendLine("## Fix Strategy")
  appendLine()
  appendLine("- Prefer root-cause fixes over suppressions or TODO comments.")
  appendLine("- Keep changes aligned with the project's existing conventions and build tooling.")
  appendLine("- Call out any blocker that requires a maintainer decision instead of guessing.")
}

internal fun areaReviewContent(summary: String, area: String): String {
  val areaLabel = area.replace("-", " ")
  return buildString {
    appendLine("## Focus")
    appendLine()
    appendLine(summary)
    appendLine()
    appendLine("## Review Triggers")
    appendLine()
    appendLine("- Changes that primarily affect $areaLabel concerns for this platform.")
    appendLine("- Project-specific cues, modules, or file patterns that should route into this specialist.")
    appendLine()
    appendLine("## Review Guidance")
    appendLine()
    appendLine("- Record the concrete risks this specialist should prioritize.")
    appendLine("- Note any repo-specific heuristics, invariants, or failure modes worth checking.")
    appendLine("- Keep output format, telemetry, and runtime ceremony in the wrapper or shared sidecars.")
  }
}

internal fun baselineReviewContent(summary: String): String = buildString {
  appendLine("## Review Focus")
  appendLine()
  appendLine(summary)
  appendLine()
  appendLine("## Review Guidance")
  appendLine()
  appendLine("- Document the project-specific risks, heuristics, and judgment calls this skill should apply.")
  appendLine("- Call out any local modules, file patterns, frameworks, or product areas that should bias review.")
  appendLine("- Capture repo-specific routing cues here only when they matter to review behavior after selection.")
  appendLine("- Keep shell ceremony, output formatting rules, and telemetry mechanics out of this file.")
  appendLine("- Reference governed add-ons here only when they enrich an already-routed platform skill.")
}

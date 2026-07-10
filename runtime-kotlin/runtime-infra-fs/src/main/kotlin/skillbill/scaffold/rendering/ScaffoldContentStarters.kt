package skillbill.scaffold.rendering

internal fun qualityCheckContent(summary: String): String = buildString {
  appendLine("## Purpose")
  appendLine()
  appendLine(summary)
  appendLine()
  appendLine("## Execution Steps")
  appendLine()
  appendLine("1. Determine the files in scope for the current unit of work.")
  appendLine(
    "2. Discover commands from repository build files, wrappers, and CI configuration " +
      "before falling back to defaults.",
  )
  appendLine("3. Run the pack's quality-check entrypoint and capture the scoped failures.")
  appendLine("4. Fix only failures that belong to the scoped work unless the contract says otherwise.")
  appendLine()
  appendLine("## Fix Strategy")
  appendLine()
  appendLine("- Follow a priority-ordered fix ladder and never suppress failures or add TODO comments.")
  appendLine("- Keep changes aligned with the project's existing conventions and build tooling.")
  appendLine("- Re-run targeted checks after each fix category.")
  appendLine("- Escalate to the full suite when targeted checks cannot establish safety.")
  appendLine("- Call out any blocker that requires a maintainer decision instead of guessing.")
}

internal fun areaReviewContent(summary: String, area: String, stackLabel: String): String {
  val areaLabel = area.replace("-", " ")
  return buildString {
    appendLine("## Focus")
    appendLine()
    appendLine(summary)
    appendLine()
    appendLine("## Ignore")
    appendLine()
    appendLine("- Findings outside $areaLabel concerns or unsupported by a concrete consequence.")
    appendLine("- Style-only preferences, formatting, and naming bikeshedding.")
    when (area) {
      "ui" -> {
        appendLine(
          "- Defer accessibility concerns to the ux-accessibility specialist " +
            "and security concerns to the security specialist.",
        )
      }
      "ux-accessibility" -> {
        appendLine(
          "- Defer UI correctness concerns to the ui specialist " +
            "and security concerns to the security specialist.",
        )
      }
    }
    appendLine()
    appendLine("## Applicability")
    appendLine()
    appendLine("Use this specialist when changed code primarily affects $areaLabel concerns for this platform.")
    appendLine()
    appendLine("## Project-Specific Rules")
    appendLine()
    appendLine("### Review Rules")
    appendLine()
    appendLine(areaStarterRule(stackLabel, area))
    appendLine("- Keep output format, telemetry, and runtime ceremony in the wrapper or shared sidecars.")
    appendLine("- For Blocker or Major findings, describe the concrete consequence explicitly.")
  }
}

private fun areaStarterRule(stackLabel: String, area: String): String {
  val stack = stackLabel.ifBlank { "Platform" }
  val (surface, consequence) = when (area) {
    "architecture" -> "module and dependency APIs" to "a dependency cycle or ownership boundary failure"
    "performance" -> "hot-path and resource APIs" to "a measurable latency, memory, or throughput failure"
    "platform-correctness" -> "lifecycle and concurrency APIs" to "an invalid state or ordering failure"
    "security" -> "authentication and sensitive-data APIs" to "an authorization or data-exposure failure"
    "testing" -> "test and fixture APIs" to "an undetected regression or false-positive test failure"
    "api-contracts" -> "request and serialization APIs" to "a compatibility or validation failure"
    "persistence" -> "transaction and storage APIs" to "a consistency or durability failure"
    "reliability" -> "timeout and retry APIs" to "an availability, duplication, or cleanup failure"
    "ui" -> "UI state and rendering APIs" to "an observable interaction or rendering failure"
    "ux-accessibility" -> "semantics and input APIs" to "an accessibility or task-completion failure"
    else -> "$area APIs" to "a concrete invariant or boundary failure"
  }
  return "- Verify `$stack $surface` preserve their documented invariants; reject $consequence."
}

internal fun baselineReviewContent(summary: String): String = buildString {
  appendLine("## Classification Rules")
  appendLine()
  appendLine(summary)
  appendLine("- If the platform's strong signals dominate, select this pack.")
  appendLine("- Otherwise, select the adjacent pack whose declared signals dominate.")
  appendLine()
  appendLine("## Diff-Signal Routing Table")
  appendLine()
  appendLine("Map changed-file names, extensions, imports, and framework markers to the matching specialists.")
  appendLine()
  appendLine("## Mixed Diffs")
  appendLine()
  appendLine("Keep the baseline specialists for the whole review and use lightweight file-level classification.")
  appendLine("Exclude generated, vendored, and non-stack-owned files from every specialist's scope.")
  appendLine()
  appendLine("## Finding Discipline")
  appendLine()
  appendLine("Calibrate severity and verify each finding's preconditions before reporting it.")
  appendLine("Keep findings attributed to their specialist lane, then deduplicate overlapping findings.")
}

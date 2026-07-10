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
    appendLine(canonicalSeverityCloser(area))
  }
}

private fun areaStarterRule(stackLabel: String, area: String): String {
  val stack = stackLabel.ifBlank { "Platform" }
  val rule = reviewAreaRule(area)
  return "- Verify `$stack ${rule.surface}` preserve their documented invariants; reject ${rule.failure}."
}

internal fun canonicalSeverityCloser(area: String): String =
  "- For Blocker or Major findings, describe the concrete ${reviewAreaRule(area).consequenceScenario} scenario."

private fun reviewAreaRule(area: String): ReviewAreaRule = when (area) {
  "architecture" -> ReviewAreaRule(
    "module and dependency APIs",
    "a dependency cycle or ownership boundary failure",
    "dependency-cycle or ownership-boundary failure",
  )
  "performance" -> ReviewAreaRule(
    "hot-path and resource APIs",
    "a measurable latency, memory, or throughput failure",
    "latency, memory-pressure, or throughput failure",
  )
  "platform-correctness" -> ReviewAreaRule(
    "lifecycle and concurrency APIs",
    "an invalid state or ordering failure",
    "invalid-state or ordering failure",
  )
  "security" -> ReviewAreaRule(
    "authentication and sensitive-data APIs",
    "an authorization or data-exposure failure",
    "authorization-bypass or data-exposure",
  )
  "testing" -> ReviewAreaRule(
    "test and fixture APIs",
    "an undetected regression or false-positive test failure",
    "undetected-regression or false-positive test",
  )
  "api-contracts" -> ReviewAreaRule(
    "request and serialization APIs",
    "a compatibility or validation failure",
    "compatibility or validation failure",
  )
  "persistence" -> ReviewAreaRule(
    "transaction and storage APIs",
    "a consistency or durability failure",
    "data-loss, consistency, or durability failure",
  )
  "reliability" -> ReviewAreaRule(
    "timeout and retry APIs",
    "an availability, duplication, or cleanup failure",
    "availability, duplication, or cleanup failure",
  )
  "ui" -> ReviewAreaRule(
    "UI state and rendering APIs",
    "an observable interaction or rendering failure",
    "user-visible interaction or rendering failure",
  )
  "ux-accessibility" -> ReviewAreaRule(
    "semantics and input APIs",
    "an accessibility or task-completion failure",
    "accessibility or task-completion failure",
  )
  else -> ReviewAreaRule(
    "$area APIs",
    "a concrete invariant or boundary failure",
    "invariant or boundary failure",
  )
}

private data class ReviewAreaRule(
  val surface: String,
  val failure: String,
  val consequenceScenario: String,
)

internal fun baselineReviewContent(summary: String): String = buildString {
  appendLine("## Classification Rules")
  appendLine()
  appendLine(summary)
  appendLine("- If the platform's strong signals dominate, select this pack.")
  appendLine("- Otherwise, select the adjacent pack whose declared signals dominate.")
  appendLine()
  appendLine("## Diff-Signal Routing Table")
  appendLine()
  appendLine("- Module boundaries, dependency declarations, or ownership crossings -> `architecture` specialist.")
  appendLine("- Hot paths, blocking calls, allocation sites, or resource use -> `performance` specialist.")
  appendLine("- Lifecycle, concurrency, state-machine, or runtime API changes -> `platform-correctness` specialist.")
  appendLine("- Authentication, authorization, untrusted input, secrets, or sensitive data -> `security` specialist.")
  appendLine("- Tests, fixtures, assertions, or regression coverage -> `testing` specialist.")
  appendLine("- Requests, responses, serialization, validation, or compatibility -> `api-contracts` specialist.")
  appendLine("- Transactions, queries, migrations, storage, or consistency -> `persistence` specialist.")
  appendLine("- Timeouts, retries, background work, cleanup, or observability -> `reliability` specialist.")
  appendLine("- Rendering, visual state, interaction, or UI framework changes -> `ui` specialist.")
  appendLine("- Semantics, focus, keyboard input, assistive technology, or task flow -> `ux-accessibility` specialist.")
  appendLine()
  appendLine("## Mixed Diffs")
  appendLine()
  appendLine("Keep the baseline specialists for the whole review and use lightweight file-level classification.")
  appendLine("Exclude generated, vendored, and non-stack-owned files from every specialist's scope.")
  appendLine(
    "When selected specialists exceed worker capacity, run them in deterministic waves " +
      "and retain every selected specialist result.",
  )
  appendLine()
  appendLine("## Finding Discipline")
  appendLine()
  appendLine("Calibrate severity and verify each finding's preconditions before reporting it.")
  appendLine("Keep findings attributed to their specialist lane through merge.")
  appendLine("Deduplicate overlapping findings without losing evidence.")
}

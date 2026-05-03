package skillbill.scaffold

private const val DEFAULT_CODEX_MAX_THREADS = 6

internal fun renderCodexAgentTomlStub(name: String, parentSkill: String): String {
  val description = "TODO: one-line description for the $name specialist subagent. Fill in before shipping."
  return buildString {
    appendLine("name = \"$name\"")
    appendLine("description = \"$description\"")
    appendLine()
    appendLine("developer_instructions = \"\"\"")
    appendLine("# ${titleCaseSpecialist(name)} Specialist")
    appendLine()
    appendLine("TODO: replace this placeholder with the specialist briefing.")
    appendLine()
    appendLine(
      "Specialist contract pointer: see specialist-contract.md for the F-XXX Risk Register format used by " +
        "this orchestrator's review specialists (parent skill: $parentSkill).",
    )
    appendLine("\"\"\"")
  }
}

internal fun renderOpencodeAgentMdStub(name: String, parentSkill: String): String {
  val description = "TODO: one-line description for the $name specialist subagent. Fill in before shipping."
  return buildString {
    appendLine("---")
    appendLine("name: $name")
    appendLine("description: $description")
    appendLine("mode: subagent")
    appendLine("---")
    appendLine()
    appendLine("# ${titleCaseSpecialist(name)} Specialist")
    appendLine()
    appendLine("TODO: replace this placeholder with the specialist briefing.")
    appendLine()
    appendLine(
      "Specialist contract pointer: see specialist-contract.md for the F-XXX Risk Register format used by " +
        "this orchestrator's review specialists (parent skill: $parentSkill).",
    )
  }
}

internal fun renderSubagentSpawnRuntimeNotes(orchestratorName: String, specialists: List<String>): String {
  if (specialists.isEmpty()) {
    return ""
  }
  val paragraphs = mutableListOf(
    "## Subagent Spawn Runtime Notes",
    subagentResolutionParagraph(orchestratorName, specialists),
  )
  if (specialists.size > DEFAULT_CODEX_MAX_THREADS) {
    paragraphs +=
      "Selected fan-out exceeds Codex's `agents.max_threads = 6` default; run waves of at most 6 specialists, " +
      "with the orchestrator merging wave outputs before final review."
  }
  paragraphs +=
    "OpenCode does not document a different native concurrency cap; keep the conservative limit of 6 or fewer " +
    "specialists per wave."
  return paragraphs.joinToString("\n\n")
}

private fun subagentResolutionParagraph(orchestratorName: String, specialists: List<String>): String {
  val exampleSpecialist = specialists.first()
  val backticked = specialists.joinToString(", ") { "`@$it`" }
  return "Specialist spawn instructions in this orchestrator are runtime-neutral. Each phrase such as " +
    "\"spawn the `$exampleSpecialist` subagent\" maps to the native subagent surface of the host runtime. " +
    "On Claude, the spawn becomes an `Agent` tool call against a matching subagent definition for " +
    "`$orchestratorName`. On Codex, the spawn is a natural-language directive and Codex resolves it by " +
    "`name` against the installed TOML files in the Codex user agents directory (with the legacy Agents " +
    "agents fallback), respecting `agents.max_threads` and `agents.max_depth`. On OpenCode, the spawn " +
    "resolves by filename-derived `name` against markdown agents installed in the OpenCode user agents " +
    "directory; operators can also invoke the same specialists manually with $backticked."
}

private fun titleCaseSpecialist(name: String): String =
  name.split("-").filter { it.isNotBlank() }.joinToString(" ") { part ->
    part.replaceFirstChar { first -> if (first.isLowerCase()) first.titlecase() else first.toString() }
  }

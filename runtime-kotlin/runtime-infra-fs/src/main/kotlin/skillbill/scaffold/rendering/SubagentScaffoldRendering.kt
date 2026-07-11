package skillbill.scaffold.rendering

import skillbill.nativeagent.composition.NativeAgentCompositionDirective
import skillbill.nativeagent.composition.NativeAgentCompositionKind
import skillbill.nativeagent.composition.NativeAgentSource
import skillbill.nativeagent.composition.renderNativeAgentBundle
import skillbill.nativeagent.composition.renderNativeAgentSource

private const val DEFAULT_CODEX_MAX_THREADS = 6

internal fun renderNativeAgentSourceStub(name: String, parentSkill: String): String {
  return renderNativeAgentSource(
    NativeAgentSource(
      name = name,
      description = "TODO: one-line description for the $name specialist subagent. Fill in before shipping.",
      body = nativeAgentStubBody(name, parentSkill),
    ),
  )
}

internal fun renderNativeAgentBundleStubs(
  names: List<String>,
  descriptions: Map<String, String> = emptyMap(),
  bodyNames: Set<String> = emptySet(),
): String = renderNativeAgentBundle(
  names.map { name ->
    NativeAgentSource(
      name = name,
      description = descriptions[name]
        ?: "TODO: one-line description for the $name specialist subagent. Fill in before shipping.",
      body = if (name in bodyNames) "Review the requested scope and return concrete findings." else "",
      composition = if (name in bodyNames) {
        null
      } else {
        NativeAgentCompositionDirective(NativeAgentCompositionKind.GovernedContent)
      },
    )
  },
)

internal fun renderSubagentSpawnRuntimeNotes(orchestratorName: String, specialists: List<String>): String {
  if (specialists.isEmpty()) {
    return ""
  }
  val paragraphs = mutableListOf(
    "### Subagent Spawn Runtime Notes",
    subagentResolutionParagraph(orchestratorName, specialists),
    claudeSpawnParagraph(orchestratorName, specialists),
    codexSpawnParagraph(),
    openCodeSpawnParagraph(specialists),
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
  return "Specialist spawn instructions in this orchestrator are runtime-neutral. Each phrase such as " +
    "\"spawn the `$exampleSpecialist` subagent\" maps to the native subagent surface of the host runtime " +
    "(parent skill: `$orchestratorName`). The per-runtime paragraphs below are imperative: the orchestrator " +
    "MUST follow the paragraph that matches the runtime it is running in, including how it collects the " +
    "subagent's `RESULT:` JSON. Picking a different mechanism (for example, emitting a natural-language " +
    "\"please spawn\" message instead of calling the listed tool) causes the workflow to stall because no " +
    "subagent actually runs and no `RESULT:` is ever returned."
}

private fun claudeSpawnParagraph(orchestratorName: String, specialists: List<String>): String {
  val exampleSpecialist = specialists.first()
  return "**On Claude (Claude Code, Anthropic SDK agents).** The orchestrator MUST invoke the built-in `Agent` " +
    "tool with `subagent_type` set to the matching specialist name (for example, `subagent_type: " +
    "\"$exampleSpecialist\"` for that role in `$orchestratorName`) and pass the per-phase briefing as the " +
    "tool's `prompt`. Call the tool in the **foreground** (the default — do NOT pass " +
    "`run_in_background: true`). The `Agent` tool blocks until the subagent finishes and returns the " +
    "subagent's final text message as the tool result; the orchestrator parses the `RESULT:` JSON directly " +
    "from that returned message in the same turn. Do NOT sleep, poll, ping, re-call, or otherwise check on " +
    "the subagent — Claude's `Agent` tool surfaces completion synchronously and any polling loop is both " +
    "unnecessary and explicitly discouraged by the tool contract. If the tool returns and the message is " +
    "missing a `RESULT:` block or contains malformed JSON, fall through to the `RESULT:` block parsing " +
    "tolerance rules (best-effort recovery, then exactly one corrective re-spawn via another foreground " +
    "`Agent` call) instead of waiting."
}

private fun codexSpawnParagraph(): String =
  "**On Codex.** The spawn is a natural-language directive in the orchestrator's turn. Codex resolves the " +
    "subagent by `name` against the installed TOML files in the Codex user agents directory (with the legacy " +
    "Agents agents fallback), respecting `agents.max_threads` and `agents.max_depth`. Because Codex runs " +
    "subagents asynchronously, the orchestrator MUST poll for completion between turns before consuming the " +
    "subagent's `RESULT:` block — do not proceed to the next phase until the subagent has visibly finished " +
    "and its `RESULT:` JSON is available in the conversation."

private fun openCodeSpawnParagraph(specialists: List<String>): String {
  val backticked = specialists.joinToString(", ") { "`@$it`" }
  return "**On OpenCode.** The spawn resolves by filename-derived `name` against markdown agents installed in " +
    "the OpenCode user agents directory; operators can also invoke the same specialists manually with " +
    "$backticked. Like Codex, OpenCode runs the spawn asynchronously, so the orchestrator MUST wait for the " +
    "subagent to finish (checking between turns) and only then read its `RESULT:` JSON before advancing."
}

private fun nativeAgentStubBody(name: String, parentSkill: String): String = buildString {
  appendLine("# ${titleCaseSpecialist(name)} Specialist")
  appendLine()
  appendLine("TODO: replace this placeholder with the specialist briefing.")
  appendLine()
  appendLine(
    "Specialist contract pointer: see specialist-contract.md for the F-XXX Risk Register format used by " +
      "this orchestrator's review specialists (parent skill: $parentSkill).",
  )
}.trimEnd()

private fun titleCaseSpecialist(name: String): String =
  name.split("-").filter { it.isNotBlank() }.joinToString(" ") { part ->
    part.replaceFirstChar { first -> if (first.isLowerCase()) first.titlecase() else first.toString() }
  }

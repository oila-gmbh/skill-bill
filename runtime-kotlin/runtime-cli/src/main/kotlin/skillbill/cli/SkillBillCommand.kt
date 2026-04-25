@file:Suppress("SpreadOperator")

package skillbill.cli

import com.github.ajalt.clikt.completion.completionOption
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject

@Inject
class SkillBillCommand(
  private val state: CliRunState,
  commands: TopLevelCliCommands,
) : DocumentedCliCommand(
  "skill-bill",
  "Import Skill Bill review output, triage findings, manage learnings, " +
    "scaffold governed skills, and inspect telemetry.",
) {
  private val dbOverride by option(
    "--db",
    help = "Optional SQLite path. Defaults to SKILL_BILL_DB or the standard local state path.",
  )

  init {
    completionOption()
    subcommands(*commands.rootCommands.toTypedArray())
  }

  override fun aliases(): Map<String, List<String>> = mapOf(
    "feature-implement-stats" to listOf("implement-stats"),
    "feature-verify-stats" to listOf("verify-stats"),
  )

  override fun run() {
    state.dbOverride = dbOverride
  }
}

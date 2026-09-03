package skillbill.cli.core

import com.github.ajalt.clikt.completion.completionOption
import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject

internal fun ParameterHolder.dbPathOverrideOption() = option(
  "--db",
  help = "Optional SQLite path. Defaults to SKILL_BILL_DB or the standard local state path.",
)

internal fun ParameterHolder.userHomeOverrideOption() = option(
  "--home",
  help = "User home directory for install/runtime path detection.",
)

@Inject
class SkillBillCommand(
  commands: TopLevelCliCommands,
) : DocumentedCliCommand(
  "skill-bill",
  "Import Skill Bill review output, triage findings, manage learnings, " +
    "scaffold governed skills, and inspect telemetry.",
) {
  init {
    registerOption(dbPathOverrideOption())
    registerOption(userHomeOverrideOption())
    completionOption()
    subcommands(commands.rootCommands)
  }

  override fun aliases(): Map<String, List<String>> = mapOf(
    "feature-verify-stats" to listOf("verify-stats"),
    "feature-task-runtime-stats" to listOf("runtime-stats"),
  )

  override fun run() = Unit
}

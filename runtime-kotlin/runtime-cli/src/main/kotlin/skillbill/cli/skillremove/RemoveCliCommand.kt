@file:Suppress("MaxLineLength")

package skillbill.cli.skillremove

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.application.scaffold.SkillRemoveService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.formatOption

@Inject
class RemoveCliCommand(
  private val state: CliRunState,
  private val skillRemoveService: SkillRemoveService,
) : DocumentedCliCommand(
  "remove",
  "Remove a horizontal skill, a platform pack, or a governed add-on, including manifest, README, " +
    "and agent-symlink cleanup.",
) {
  private val target by argument(
    help = "Removal target. Examples: 'skill:bill-foo', 'platform:my-platform', " +
      "'addon:platform-packs/kmp/addons/my-addon.md'.",
  ).optional()
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to operate on. Defaults to the current working directory.",
  )
    .default(".")
  private val dryRun by option("--dry-run", help = "Preview the removal cascade without touching disk.")
    .flag(default = false)
  private val allowShipped by option(
    "--allow-shipped",
    help = "Allow removal of shipped product surfaces such as bill-* skills. " +
      "'.bill-shared' is never removable.",
  ).flag(default = false)
  private val format by formatOption()

  override fun run() {
    state.result = executeRemoveCommand(
      RemoveCommandExecutionRequest(
        state = state,
        skillRemoveService = skillRemoveService,
        rawTarget = target,
        repoRoot = repoRoot,
        dryRun = dryRun,
        allowShipped = allowShipped,
        format = format,
      ),
    )
  }
}

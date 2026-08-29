@file:Suppress("MaxLineLength", "TooManyFunctions", "LongParameterList", "ReturnCount", "ThrowsCount")

package skillbill.cli.scaffold

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import me.tatarka.inject.annotations.Inject
import skillbill.application.scaffold.ScaffoldService
import skillbill.application.scaffold.UnsupportedScaffoldService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.DocumentedNoOpCliCommand
import skillbill.cli.core.formatOption
import skillbill.cli.install.InstallApplyCommand
import skillbill.cli.install.InstallApplyExternalAddonsCommand
import skillbill.cli.install.InstallClaudeAgentsPathCommand
import skillbill.cli.install.InstallClaudeRootsCommand
import skillbill.cli.install.InstallCleanupAgentTargetCommand
import skillbill.cli.install.InstallCodexAgentsPathCommand
import skillbill.cli.install.InstallCodexRootsCommand
import skillbill.cli.install.InstallCursorAgentsPathCommand
import skillbill.cli.install.InstallJunieAgentsPathCommand
import skillbill.cli.install.InstallLinkClaudeAgentsCommand
import skillbill.cli.install.InstallLinkCodexAgentsCommand
import skillbill.cli.install.InstallLinkCursorAgentsCommand
import skillbill.cli.install.InstallLinkJunieAgentsCommand
import skillbill.cli.install.InstallPlanCommand
import skillbill.cli.install.InstallReconcileCommand
import skillbill.cli.install.InstallRegisterMcpCommand
import skillbill.cli.install.InstallReplayLastSelectionCommand
import skillbill.cli.install.InstallUnlinkClaudeAgentsCommand
import skillbill.cli.install.InstallUnlinkCodexAgentsCommand
import skillbill.cli.install.InstallUnlinkCursorAgentsCommand
import skillbill.cli.install.InstallUnlinkJunieAgentsCommand
import skillbill.cli.install.InstallUnregisterMcpCommand
import java.nio.file.Path

@Inject
class ScaffoldTopLevelCommands(
  listSkillsCommand: ListSkillsCommand,
  showSkillCommand: ShowSkillCommand,
  explainSkillCommand: ExplainSkillCommand,
  validateSkillCommand: ValidateSkillCommand,
  upgradeSkillsCommand: UpgradeSkillsCommand,
  renderSkillsCommand: RenderSkillsCommand,
  editSkillCommand: EditSkillCommand,
  fillSkillCommand: FillSkillCommand,
  newSkillCommand: NewSkillCommand,
  newCommand: NewCommand,
  createAndFillCommand: CreateAndFillCommand,
  newAddonCommand: NewAddonCommand,
  installCommands: InstallTopLevelCommands,
) {
  val newSkill = newSkillCommand
  val newAlias = newCommand
  val createAndFill = createAndFillCommand
  val newAddon = newAddonCommand
  val install = installCommands
  val commands: List<CliktCommand> =
    listOf(
      listSkillsCommand,
      showSkillCommand,
      explainSkillCommand,
      validateSkillCommand,
      upgradeSkillsCommand,
      renderSkillsCommand,
      editSkillCommand,
      fillSkillCommand,
      newSkill,
      newAlias,
      createAndFill,
      newAddon,
      install.command,
    )
}

@Inject
class InstallTopLevelCommands(
  planCommand: InstallPlanCommand,
  applyCommand: InstallApplyCommand,
  applyExternalAddonsCommand: InstallApplyExternalAddonsCommand,
  reconcileCommand: InstallReconcileCommand,
  replayLastSelectionCommand: InstallReplayLastSelectionCommand,
  agentPathCommand: InstallAgentPathCommand,
  detectAgentsCommand: InstallDetectAgentsCommand,
  claudeRootsCommand: InstallClaudeRootsCommand,
  codexRootsCommand: InstallCodexRootsCommand,
  linkSkillCommand: InstallLinkSkillCommand,
  codexAgentsPathCommand: InstallCodexAgentsPathCommand,
  claudeAgentsPathCommand: InstallClaudeAgentsPathCommand,
  junieAgentsPathCommand: InstallJunieAgentsPathCommand,
  cursorAgentsPathCommand: InstallCursorAgentsPathCommand,
  cleanupAgentTargetCommand: InstallCleanupAgentTargetCommand,
  linkClaudeAgentsCommand: InstallLinkClaudeAgentsCommand,
  unlinkClaudeAgentsCommand: InstallUnlinkClaudeAgentsCommand,
  linkCodexAgentsCommand: InstallLinkCodexAgentsCommand,
  unlinkCodexAgentsCommand: InstallUnlinkCodexAgentsCommand,
  linkJunieAgentsCommand: InstallLinkJunieAgentsCommand,
  unlinkJunieAgentsCommand: InstallUnlinkJunieAgentsCommand,
  linkCursorAgentsCommand: InstallLinkCursorAgentsCommand,
  unlinkCursorAgentsCommand: InstallUnlinkCursorAgentsCommand,
  registerMcpCommand: InstallRegisterMcpCommand,
  unregisterMcpCommand: InstallUnregisterMcpCommand,
) {
  val command: DocumentedNoOpCliCommand =
    object : DocumentedNoOpCliCommand(
      "install",
      "Install-side primitives (agent paths, symlinks, native subagents, MCP registration).",
    ) {}
      .subcommands(
        planCommand,
        applyCommand,
        applyExternalAddonsCommand,
        reconcileCommand,
        replayLastSelectionCommand,
        agentPathCommand,
        detectAgentsCommand,
        claudeRootsCommand,
        codexRootsCommand,
        linkSkillCommand,
        codexAgentsPathCommand,
        claudeAgentsPathCommand,
        junieAgentsPathCommand,
        cursorAgentsPathCommand,
        cleanupAgentTargetCommand,
        linkClaudeAgentsCommand,
        unlinkClaudeAgentsCommand,
        linkCodexAgentsCommand,
        unlinkCodexAgentsCommand,
        linkJunieAgentsCommand,
        unlinkJunieAgentsCommand,
        linkCursorAgentsCommand,
        unlinkCursorAgentsCommand,
        registerMcpCommand,
        unregisterMcpCommand,
      )
}

@Inject
class ListSkillsCommand(
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
) : DocumentedCliCommand("list", "List governed skills and agent add-ons with their authoring and validation status.") {
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to inspect. Defaults to the current working directory.",
  )
    .default(".")
  private val skillNames by option(
    "--skill-name",
    help = "Optional governed skill or agent-addon:<slug> identity. Repeat to target multiple entries.",
  ).multiple()
  private val format by formatOption()

  override fun run() {
    state.result =
      authoringResult(format) {
        scaffoldService.list(Path.of(repoRoot), skillNames).toCliMap()
      }
  }
}

@Inject
class ShowSkillCommand(
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
) : DocumentedCliCommand(
  "show",
  "Show one governed skill or agent-addon:<slug> with authored source and metadata.",
) {
  private val skillName by argument(help = "Governed skill name to inspect.")
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to inspect. Defaults to the current working directory.",
  )
    .default(".")
  private val content by option("--content", help = "How much content.md text to include.")
    .choice("none", "preview", "full")
    .default("preview")
  private val format by formatOption()

  override fun run() {
    state.result =
      authoringResult(format) {
        scaffoldService.show(Path.of(repoRoot), skillName, content).toCliMap()
      }
  }
}

@Inject
class ExplainSkillCommand(
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
) : DocumentedCliCommand(
  "explain",
  "Explain the governed authoring boundary and the CLI workflow for content-managed skills.",
) {
  private val skillName by argument(help = "Optional governed skill name to explain with concrete paths.").optional()
  private val repoRoot by option("--repo-root", help = "Repo root to inspect when explaining one skill.").default(".")
  private val format by formatOption()

  override fun run() {
    state.result =
      authoringResult(format) {
        scaffoldService.explain(Path.of(repoRoot), skillName).toCliMap()
      }
  }
}

@Inject
class ValidateSkillCommand(
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
) : DocumentedCliCommand("validate", "Run the repo validator, or validate specific skills only.") {
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to validate. Defaults to the current working directory.",
  )
    .default(".")
  private val skillNames by option(
    "--skill-name",
    help = "Optional skill name to validate in isolation. Repeat to target multiple skills.",
  ).multiple()
  private val format by formatOption()

  override fun run() {
    state.result =
      authoringResult(format, successExitCode = { payload -> if (payload["status"] == "pass") 0 else 1 }) {
        scaffoldService.validate(Path.of(repoRoot), skillNames).toCliMap()
      }
  }
}

@Inject
class UpgradeSkillsCommand(
  private val state: CliRunState,
  scaffoldService: ScaffoldService,
) : WrapperRegenerationCommand("upgrade", state, scaffoldService)

@Inject
class RenderSkillsCommand(
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
) : DocumentedCliCommand("render", "Render scaffold-managed files to stdout without writing to disk.") {
  private val positionalSkillName by argument(help = "Governed skill name to render.").optional()
  private val optionSkillName by option("--skill-name", help = "Governed skill name to render.")
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to inspect. Defaults to the current working directory.",
  )
    .default(".")
  private val dryRun by option("--dry-run", help = "Accepted no-op alias for read-only render output.")
    .flag(default = false)

  override fun run() {
    val skillName =
      when {
        positionalSkillName != null && optionSkillName != null ->
          throw UsageError("Provide the skill name either as an argument or with --skill-name, not both.")
        positionalSkillName != null -> requireNotNull(positionalSkillName)
        optionSkillName != null -> requireNotNull(optionSkillName)
        else -> throw UsageError("Provide a skill name as an argument or with --skill-name.")
      }
    completeRenderText(state, Path.of(repoRoot), skillName, dryRun, scaffoldService)
  }
}

open class WrapperRegenerationCommand(
  name: String,
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
) : DocumentedCliCommand(name, "Validate governed render output and regenerate native-agent artifacts.") {
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to upgrade. Defaults to the current working directory.",
  )
    .default(".")
  private val skipValidate by option("--skip-validate", help = "Skip validation after wrapper regeneration.")
    .flag(default = false)
  private val skillNames by option(
    "--skill-name",
    help = "Optional governed or horizontal skill name to regenerate. Repeat to target multiple skills.",
  ).multiple()
  private val format by formatOption()

  override fun run() {
    state.result =
      authoringResult(format) {
        scaffoldService.upgrade(Path.of(repoRoot), skillNames, validate = !skipValidate).toCliMap()
      }
  }
}

@Inject
class EditSkillCommand(
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
  private val unsupportedScaffoldService: UnsupportedScaffoldService,
) : DocumentedCliCommand("edit", "Edit a content-managed skill's authored content.md and validate render output.") {
  private val skillName by argument(help = "Governed skill name to edit.")
  private val repoRoot by option("--repo-root", help = "Repo root to edit. Defaults to the current working directory.")
    .default(".")
  private val bodyFile by option("--body-file", help = "Replace content.md from a file path (or '-' for stdin).")
  private val editor by option("--editor", help = "Open content.md in \$VISUAL or \$EDITOR.").flag(default = false)
  private val section by option("--section", help = "Optional authored H2 section name to edit in isolation.")
  private val format by formatOption()

  override fun run() {
    state.result =
      when {
        editor ->
          unsupportedNativeScaffoldResult(
            unsupportedScaffoldService.retiredUnsupportedMessage(
              "edit --editor",
              "skill-bill fill $skillName --body-file <file>",
              editor = true,
            ),
            format,
          )
        bodyFile != null ->
          authoringResult(format) {
            scaffoldService.editWithBodyFile(
              Path.of(repoRoot),
              skillName,
              readCliTextFile(bodyFile.orEmpty(), state),
              section,
            ).toCliMap()
          }
        else ->
          unsupportedNativeScaffoldResult(
            unsupportedScaffoldService.retiredUnsupportedMessage(
              "edit",
              "skill-bill fill $skillName --body-file <file>",
              editor = false,
            ),
            format,
          )
      }
  }
}

@Inject
class FillSkillCommand(
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
) : DocumentedCliCommand("fill", "Write authored content into content.md and validate render output.") {
  private val skillName by argument(help = "Governed skill name to fill.")
  private val repoRoot by option("--repo-root", help = "Repo root to edit. Defaults to the current working directory.")
    .default(".")
  private val body by option("--body", help = "Body text to write.")
  private val bodyFile by option("--body-file", help = "Read body text from a file path or '-' for stdin.")
  private val section by option("--section", help = "Optional authored H2 section name to replace.")
  private val format by formatOption()

  override fun run() {
    state.result =
      when {
        body != null && bodyFile != null -> errorResult("--body and --body-file are mutually exclusive.", format)
        body == null && bodyFile == null -> errorResult("Either --body or --body-file is required.", format)
        else ->
          authoringResult(format) {
            scaffoldService.fill(
              Path.of(repoRoot),
              skillName,
              body ?: readCliTextFile(bodyFile.orEmpty(), state),
              section,
            ).toCliMap()
          }
      }
  }
}

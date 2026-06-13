@file:Suppress("MaxLineLength", "TooManyFunctions", "LongParameterList", "ReturnCount", "ThrowsCount")

package skillbill.cli.scaffold

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import me.tatarka.inject.annotations.Inject
import skillbill.application.install.InstallService
import skillbill.application.scaffold.InstallAgentService
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.application.scaffold.ScaffoldService
import skillbill.application.scaffold.UnsupportedScaffoldService
import skillbill.cli.core.CliOutput
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.DocumentedNoOpCliCommand
import skillbill.cli.core.formatOption
import skillbill.cli.install.InstallApplyCommand
import skillbill.cli.install.InstallClaudeAgentsPathCommand
import skillbill.cli.install.InstallClaudeRootsCommand
import skillbill.cli.install.InstallCleanupAgentTargetCommand
import skillbill.cli.install.InstallCodexAgentsPathCommand
import skillbill.cli.install.InstallJunieAgentsPathCommand
import skillbill.cli.install.InstallLinkClaudeAgentsCommand
import skillbill.cli.install.InstallLinkCodexAgentsCommand
import skillbill.cli.install.InstallLinkJunieAgentsCommand
import skillbill.cli.install.InstallLinkOpencodeAgentsCommand
import skillbill.cli.install.InstallOpencodeAgentsPathCommand
import skillbill.cli.install.InstallPlanCommand
import skillbill.cli.install.InstallReconcileCommand
import skillbill.cli.install.InstallRegisterMcpCommand
import skillbill.cli.install.InstallReplayLastSelectionCommand
import skillbill.cli.install.InstallUnlinkClaudeAgentsCommand
import skillbill.cli.install.InstallUnlinkCodexAgentsCommand
import skillbill.cli.install.InstallUnlinkJunieAgentsCommand
import skillbill.cli.install.InstallUnlinkOpencodeAgentsCommand
import skillbill.cli.install.InstallUnregisterMcpCommand
import skillbill.cli.install.refuseInstallMutationDuringGoalContinuation
import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliFormat
import skillbill.contracts.JsonSupport
import skillbill.error.SkillBillRuntimeException
import skillbill.ports.scaffold.model.ScaffoldRenderResult
import skillbill.scaffold.model.command.isRetiredPartialScaffoldCommandKindAlias
import skillbill.scaffold.model.command.rejectRetiredPartialScaffoldCommandKind
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val SCAFFOLD_SESSION_SUFFIX_LENGTH = 4

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
  reconcileCommand: InstallReconcileCommand,
  replayLastSelectionCommand: InstallReplayLastSelectionCommand,
  agentPathCommand: InstallAgentPathCommand,
  detectAgentsCommand: InstallDetectAgentsCommand,
  claudeRootsCommand: InstallClaudeRootsCommand,
  linkSkillCommand: InstallLinkSkillCommand,
  codexAgentsPathCommand: InstallCodexAgentsPathCommand,
  claudeAgentsPathCommand: InstallClaudeAgentsPathCommand,
  opencodeAgentsPathCommand: InstallOpencodeAgentsPathCommand,
  junieAgentsPathCommand: InstallJunieAgentsPathCommand,
  cleanupAgentTargetCommand: InstallCleanupAgentTargetCommand,
  linkClaudeAgentsCommand: InstallLinkClaudeAgentsCommand,
  unlinkClaudeAgentsCommand: InstallUnlinkClaudeAgentsCommand,
  linkCodexAgentsCommand: InstallLinkCodexAgentsCommand,
  unlinkCodexAgentsCommand: InstallUnlinkCodexAgentsCommand,
  linkOpencodeAgentsCommand: InstallLinkOpencodeAgentsCommand,
  unlinkOpencodeAgentsCommand: InstallUnlinkOpencodeAgentsCommand,
  linkJunieAgentsCommand: InstallLinkJunieAgentsCommand,
  unlinkJunieAgentsCommand: InstallUnlinkJunieAgentsCommand,
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
        reconcileCommand,
        replayLastSelectionCommand,
        agentPathCommand,
        detectAgentsCommand,
        claudeRootsCommand,
        linkSkillCommand,
        codexAgentsPathCommand,
        claudeAgentsPathCommand,
        opencodeAgentsPathCommand,
        junieAgentsPathCommand,
        cleanupAgentTargetCommand,
        linkClaudeAgentsCommand,
        unlinkClaudeAgentsCommand,
        linkCodexAgentsCommand,
        unlinkCodexAgentsCommand,
        linkOpencodeAgentsCommand,
        unlinkOpencodeAgentsCommand,
        linkJunieAgentsCommand,
        unlinkJunieAgentsCommand,
        registerMcpCommand,
        unregisterMcpCommand,
      )
}

@Inject
class ListSkillsCommand(
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
) : DocumentedCliCommand("list", "List content-managed skills and their authoring status.") {
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to inspect. Defaults to the current working directory.",
  )
    .default(".")
  private val skillNames by option(
    "--skill-name",
    help = "Optional content-managed skill name to include. Repeat to target multiple skills.",
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
  "Show one content-managed skill with section status, drift, and recommended next commands.",
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
  private val skillName by argument(help = "Governed skill name to render.")
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to inspect. Defaults to the current working directory.",
  )
    .default(".")
  private val dryRun by option("--dry-run", help = "Accepted no-op alias for read-only render output.")
    .flag(default = false)

  override fun run() {
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

@Inject
class NewSkillCommand(
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
  private val scaffoldCatalogService: ScaffoldCatalogService,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("new-skill", "Scaffold a new skill from a short wizard or payload file.") {
  private val payload by option("--payload", help = "Path to a JSON payload file (or '-' for stdin).")
  private val interactive by option(
    "--interactive",
    help = "Run the prompt wizard. This is the default when --payload is omitted.",
  )
    .flag(default = false)
  private val assisted by option(
    "--assisted",
    help = "Run the assisted wizard. It asks for scaffold kind, agent, and the minimum required inputs.",
  )
    .flag(default = false)
  private val dryRun by option("--dry-run", help = "Plan the scaffold and report the operations without touching disk.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    state.result =
      if (assisted && payload != null) {
        errorResult("--assisted cannot be combined with --payload.", format)
      } else if (assisted) {
        runNativeAssistedScaffoldWizard(
          dryRun,
          format,
          state,
          scaffoldService,
          scaffoldCatalogService,
          installAgentService,
        )
      } else if (interactive || payload == null) {
        runNativeScaffoldWizard(dryRun, format, state, scaffoldService, scaffoldCatalogService)
      } else {
        runNativeScaffoldPayload(payload, dryRun, format, state, scaffoldService)
      }
  }
}

@Inject
class NewCommand(
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
  private val scaffoldCatalogService: ScaffoldCatalogService,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("new", "Scaffold a new skill from a short wizard or payload file.") {
  private val payload by option("--payload", help = "Path to a JSON payload file (or '-' for stdin).")
  private val interactive by option(
    "--interactive",
    help = "Run the prompt wizard. This is the default when --payload is omitted.",
  )
    .flag(default = false)
  private val assisted by option(
    "--assisted",
    help = "Run the assisted wizard. It asks for scaffold kind, agent, and the minimum required inputs.",
  )
    .flag(default = false)
  private val dryRun by option("--dry-run", help = "Plan the scaffold and report the operations without touching disk.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    state.result =
      if (assisted && payload != null) {
        errorResult("--assisted cannot be combined with --payload.", format)
      } else if (assisted) {
        runNativeAssistedScaffoldWizard(
          dryRun,
          format,
          state,
          scaffoldService,
          scaffoldCatalogService,
          installAgentService,
        )
      } else if (interactive || payload == null) {
        runNativeScaffoldWizard(dryRun, format, state, scaffoldService, scaffoldCatalogService)
      } else {
        runNativeScaffoldPayload(payload, dryRun, format, state, scaffoldService)
      }
  }
}

@Inject
class CreateAndFillCommand(
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
  private val unsupportedScaffoldService: UnsupportedScaffoldService,
) : DocumentedCliCommand(
  "create-and-fill",
  "Scaffold one governed skill, then immediately author content.md and validate it.",
) {
  private val payload by option("--payload", help = "Path to a JSON payload file (or '-' for stdin).")
  private val interactive by option("--interactive", help = "Retired in SKILL-32; use --payload instead.")
    .flag(default = false)
  private val dryRun by option("--dry-run", help = "Plan the scaffold and report the operations without touching disk.")
    .flag(default = false)
  private val body by option("--body", help = "Optional authored body to write after scaffolding.")
  private val bodyFile by option(
    "--body-file",
    help = "Optional file path (or '-') to read the authored body from.",
  )
  private val editor by option(
    "--editor",
    help = "Open the scaffolded content.md in \$VISUAL or \$EDITOR.",
  )
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    state.result =
      createAndFillResult(
        payload,
        interactive,
        dryRun,
        body,
        bodyFile,
        editor,
        format,
        state,
        scaffoldService,
        unsupportedScaffoldService,
      )
  }
}

@Inject
class NewAddonCommand(
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
  private val unsupportedScaffoldService: UnsupportedScaffoldService,
) : DocumentedCliCommand("new-addon", "Create a governed add-on file inside an existing platform pack.") {
  private val platform by option("--platform", help = "Owning platform slug.")
  private val name by option(
    "--name",
    help = "Add-on slug (without a bill- prefix).",
  )
  private val body by option("--body", help = "Advanced/scripted: complete markdown body to write to the add-on file.")
  private val bodyFile by option(
    "--body-file",
    help = "Advanced/scripted: markdown file to copy into the add-on (or '-').",
  )
  private val consumerSkillDirs by option(
    "--consumer-skill-dir",
    help = "Advanced/scripted: skill-relative directory to register as an add-on consumer. May be repeated. " +
      "Defaults to the pack baseline code-review skill.",
  ).multiple()
  private val interactive by option("--interactive", help = "Retired in SKILL-32; use explicit options instead.")
    .flag(default = false)
  private val dryRun by option("--dry-run", help = "Plan the scaffold and report the operations without touching disk.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    state.result =
      if (interactive) {
        unsupportedNativeScaffoldResult(
          unsupportedScaffoldService.retiredUnsupportedMessage(
            "new-addon --interactive",
            "skill-bill new-addon --platform <platform> --name <name>",
            editor = false,
          ),
          format,
        )
      } else if (body != null && bodyFile != null) {
        errorResult("--body and --body-file are mutually exclusive.", format)
      } else {
        runNativeScaffoldPayload(
          newAddonPayload(platform, name, body, bodyFile, consumerSkillDirs, state),
          dryRun,
          format,
          scaffoldService,
        )
      }
  }
}

@Inject
class InstallAgentPathCommand(
  private val state: CliRunState,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("agent-path", "Print the canonical install directory for a given agent.") {
  private val agent by argument(help = "Agent name.")

  override fun run() {
    val path = installAgentService.agentPath(agent, state.userHome)
    state.result = CliExecutionResult(exitCode = 0, stdout = "$path\n")
  }
}

@Inject
class InstallDetectAgentsCommand(
  private val state: CliRunState,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("detect-agents", "List detected agents as 'name\\tpath' lines.") {
  override fun run() {
    val output =
      installAgentService.detectAgentTargets(state.userHome)
        .joinToString(separator = "") { target -> "${target.name}\t${target.path}\n" }
    state.result = CliExecutionResult(exitCode = 0, stdout = output)
  }
}

@Inject
class InstallLinkSkillCommand(
  private val state: CliRunState,
  private val installService: InstallService,
) : DocumentedCliCommand(
  "link-skill",
  "Symlink a skill DIRECTORY into an agent's install directory.",
) {
  private val source by option("--source", help = "Skill directory to install.").required()
  private val targetDir by option("--target-dir", help = "Target install directory.").required()
  private val agent by option("--agent", help = "Optional agent name to label the install.").default("")
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root for content-managed skills; enables generated SKILL.md install staging.",
  )

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("link-skill")) {
      return
    }
    installService.linkSkill(
      source = Path.of(source),
      targetDir = Path.of(targetDir),
      agent = agent,
      repoRoot = repoRoot?.let(Path::of),
      home = state.userHome,
    )
    state.result = CliExecutionResult(exitCode = 0, stdout = "")
  }
}

private fun runNativeScaffoldWizard(
  dryRun: Boolean,
  format: CliFormat,
  state: CliRunState,
  scaffoldService: ScaffoldService,
  scaffoldCatalogService: ScaffoldCatalogService,
): CliExecutionResult {
  val payload =
    try {
      collectScaffoldWizardPayload(state, scaffoldCatalogService)
    } catch (error: SkillBillRuntimeException) {
      return errorResult(error.message.orEmpty(), format)
    } catch (error: IllegalArgumentException) {
      return errorResult(error.message.orEmpty(), format)
    }
  return runNativeScaffoldPayload(payload, dryRun, format, scaffoldService)
}

private fun runNativeAssistedScaffoldWizard(
  dryRun: Boolean,
  format: CliFormat,
  state: CliRunState,
  scaffoldService: ScaffoldService,
  scaffoldCatalogService: ScaffoldCatalogService,
  installAgentService: InstallAgentService,
): CliExecutionResult {
  val payload =
    try {
      collectAssistedScaffoldWizardPayload(state, scaffoldCatalogService, installAgentService)
    } catch (error: SkillBillRuntimeException) {
      return errorResult(error.message.orEmpty(), format)
    } catch (error: IllegalArgumentException) {
      return errorResult(error.message.orEmpty(), format)
    }
  return runNativeScaffoldPayload(payload, dryRun, format, scaffoldService)
}

private fun collectAssistedScaffoldWizardPayload(
  state: CliRunState,
  scaffoldCatalogService: ScaffoldCatalogService,
  installAgentService: InstallAgentService,
): Map<String, Any?> {
  state.liveStdout(
    "Skill Bill assisted scaffold wizard\n" +
      "Kind: 1 horizontal, 2 platform-pack, 3 add-on\n\n",
  )
  val kind = normalizeWizardKind(promptRequired(state, "Kind"))
  val agent =
    promptAssistedAgent(state, installAgentService.detectAgentTargets(state.userHome).map { target -> target.name })
  state.liveStdout(
    "Assisted generator: $agent. Scaffold suggestions are deterministic local defaults; " +
      "agent-backed generation needs a structured scaffold output contract.\n",
  )
  return when (kind) {
    "platform-pack" -> assistedPlatformPackWizardPayload(state, scaffoldCatalogService.platformPackPresets())
    else -> throw IllegalArgumentException(
      "Assisted mode currently supports platform-pack scaffolds. Use the normal wizard for kind '$kind'.",
    )
  }
}

private fun collectScaffoldWizardPayload(
  state: CliRunState,
  scaffoldCatalogService: ScaffoldCatalogService,
): Map<String, Any?> {
  state.liveStdout(
    "Skill Bill scaffold wizard\n" +
      "Kind: 1 horizontal, 2 platform-pack, 3 add-on\n\n",
  )
  return when (val kind = normalizeWizardKind(promptRequired(state, "Kind"))) {
    "horizontal" -> horizontalWizardPayload(state)
    "platform-pack" -> platformPackWizardPayload(state, scaffoldCatalogService.platformPackPresets())
    "add-on" -> addOnWizardPayload(state)
    else -> throw IllegalArgumentException("Unsupported scaffold wizard kind '$kind'.")
  }
}

private fun horizontalWizardPayload(state: CliRunState): Map<String, Any?> = buildMap {
  putScaffoldBase("horizontal")
  put("name", normalizeBillSkillName(promptRequired(state, "Skill name")))
  promptOptional(state, "Description").ifNotBlank { description -> put("description", description) }
}

private fun platformPackWizardPayload(
  state: CliRunState,
  platformPackPresets: Map<String, String>,
): Map<String, Any?> = buildMap {
  putScaffoldBase("platform-pack")
  val platform = promptRequired(state, "Platform slug")
  put("platform", platform)
  promptOptional(state, "Display name").ifNotBlank { displayName -> put("display_name", displayName) }
  promptOptional(state, "Description").ifNotBlank { description -> put("description", description) }
  promptRoutingSignals(state, platform, platform in platformPackPresets)
    .ifNotEmpty { signals -> put("routing_signals", mapOf("strong" to signals)) }
}

private fun assistedPlatformPackWizardPayload(
  state: CliRunState,
  platformPackPresets: Map<String, String>,
): Map<String, Any?> {
  val platformInput = promptRequired(state, "Language or platform")
  return assistedPlatformPackPayload(platformInput, platformPackPresets)
}

private fun assistedPlatformPackPayload(
  platformInput: String,
  platformPackPresets: Map<String, String>,
): Map<String, Any?> = buildMap {
  val profile = assistedPlatformProfile(platformInput)
  val displayName = platformPackPresets[profile.slug] ?: profile.displayName
  putScaffoldBase("platform-pack")
  put("platform", profile.slug)
  put("display_name", displayName)
  put("description", "$displayName platform pack for code review and quality checks.")
  if (profile.slug !in platformPackPresets) {
    put("routing_signals", mapOf("strong" to profile.strongSignals))
  }
}

private fun addOnWizardPayload(state: CliRunState): Map<String, Any?> = buildMap {
  putScaffoldBase("add-on")
  put("platform", promptRequired(state, "Platform slug"))
  put("name", promptRequired(state, "Add-on name"))
  promptOptional(state, "Description").ifNotBlank { description -> put("description", description) }
}

private fun MutableMap<String, Any?>.putScaffoldBase(kind: String) {
  put("scaffold_payload_version", "1.0")
  put("kind", kind)
}

private fun normalizeWizardKind(value: String): String = when (value.trim().lowercase()) {
  "1", "horizontal", "skill" -> "horizontal"
  "2", "platform", "platform-pack", "pack" -> "platform-pack"
  "3", "add-on", "addon" -> "add-on"
  else -> if (isRetiredPartialScaffoldCommandKindAlias(value)) {
    rejectRetiredPartialScaffoldCommandKind(value)
  } else {
    value
  }
}

private fun promptDefault(state: CliRunState, label: String, default: String): String {
  val value = promptOptional(state, label)
  return value.ifBlank { default }
}

private fun promptRoutingSignals(state: CliRunState, platform: String, hasPreset: Boolean): List<String> {
  if (hasPreset) {
    state.liveStdout(
      "Built-in routing preset found for '$platform'. Press Enter to use it, or enter comma-separated " +
        "replacement signals such as file extensions, filenames, or directory markers.\n",
    )
    return parseCommaSeparated(promptOptional(state, "Routing signal override (optional, comma-separated)"))
  }
  state.liveStdout(
    "Routing signals tell Skill Bill when to use this platform pack. Enter comma-separated text " +
      "markers that strongly identify the stack in changed files, repo markers, or dependency manifests.\n" +
      "Use file extensions (.kt, .go), filenames (go.mod, package.json), directories (src/main/java), " +
      "dependency coordinates, or language markers.\n",
  )
  val signals = parseCommaSeparated(promptRequired(state, "Strong routing signals (comma-separated)"))
  require(signals.isNotEmpty()) {
    "Missing required scaffold wizard value: Strong routing signals (comma-separated)."
  }
  return signals
}

private fun promptAssistedAgent(state: CliRunState, detectedAgents: List<String>): String {
  val agents = detectedAgents.distinct().sorted()
  if (agents.isEmpty()) {
    state.liveStdout("No installed agents detected; using local deterministic assistance.\n")
    return "local"
  }
  state.liveStdout(
    "Available agents:\n" +
      agents.mapIndexed { index, agent -> "  ${index + 1}. $agent" }.joinToString(separator = "\n") +
      "\n",
  )
  val selected = promptDefault(state, "Agent [1]", "1")
  val byNumber = selected.toIntOrNull()?.let { number -> agents.getOrNull(number - 1) }
  val byName = agents.firstOrNull { agent -> agent.equals(selected, ignoreCase = true) }
  return byNumber ?: byName ?: throw IllegalArgumentException(
    "Unknown assisted agent '$selected'. Choose one of: ${agents.joinToString(", ")}.",
  )
}

private data class AssistedPlatformProfile(
  val slug: String,
  val displayName: String,
  val strongSignals: List<String>,
)

private fun assistedPlatformProfile(input: String): AssistedPlatformProfile {
  val key = languageLookupKey(input)
  return assistedPlatformProfiles()[key] ?: fallbackAssistedPlatformProfile(input)
}

private fun assistedPlatformProfiles(): Map<String, AssistedPlatformProfile> = buildMap {
  putProfile("go", "Go", listOf(".go", "go.mod", "go.sum"), "go", "golang")
  putProfile(
    "python",
    "Python",
    listOf("pyproject.toml", "requirements.txt", "setup.py", "poetry.lock", ".py"),
    "py",
    "python",
  )
  putProfile(
    "javascript",
    "JavaScript",
    listOf("package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml", ".js", ".jsx"),
    "js",
    "javascript",
    "node",
    "nodejs",
  )
  putProfile("typescript", "TypeScript", listOf("tsconfig.json", "package.json", ".ts", ".tsx"), "ts", "typescript")
  putProfile("rust", "Rust", listOf("Cargo.toml", "Cargo.lock", ".rs"), "rs", "rust")
  putProfile("ruby", "Ruby", listOf("Gemfile", "Gemfile.lock", ".rb"), "rb", "ruby")
  putProfile("csharp", "C#", listOf(".csproj", ".sln", ".cs"), "csharp", "c#", "dotnet")
  putProfile("cpp", "C++", listOf("CMakeLists.txt", ".cpp", ".hpp", ".cc", ".h"), "cpp", "c++")
  putProfile("c", "C", listOf("Makefile", ".c", ".h"), "c")
  putProfile("swift", "Swift", listOf("Package.swift", ".swift"), "swift")
  putProfile("scala", "Scala", listOf("build.sbt", ".scala"), "scala")
  putProfile("clojure", "Clojure", listOf("deps.edn", "project.clj", ".clj"), "clojure")
  putProfile("elixir", "Elixir", listOf("mix.exs", "mix.lock", ".ex", ".exs"), "elixir")
  putProfile("erlang", "Erlang", listOf("rebar.config", ".erl", ".hrl"), "erlang")
  putProfile("dart", "Dart", listOf("pubspec.yaml", ".dart"), "dart")
  putProfile("lua", "Lua", listOf(".lua"), "lua")
  putProfile("haskell", "Haskell", listOf("stack.yaml", "cabal.project", ".hs"), "haskell")
}

private fun MutableMap<String, AssistedPlatformProfile>.putProfile(
  slug: String,
  displayName: String,
  strongSignals: List<String>,
  vararg aliases: String,
) {
  val profile = AssistedPlatformProfile(slug = slug, displayName = displayName, strongSignals = strongSignals)
  aliases.forEach { alias -> put(languageLookupKey(alias), profile) }
}

private fun fallbackAssistedPlatformProfile(input: String): AssistedPlatformProfile {
  val slug = platformSlugFromInput(input)
  val displayName = displayNameFromInput(input, slug)
  return AssistedPlatformProfile(
    slug = slug,
    displayName = displayName,
    strongSignals = listOf(".$slug", "$slug/"),
  )
}

private fun languageLookupKey(value: String): String =
  value.trim().lowercase().filter { character -> character.isLetterOrDigit() || character == '#' || character == '+' }

private fun platformSlugFromInput(value: String): String = value.trim()
  .lowercase()
  .replace(Regex("[^a-z0-9]+"), "-")
  .trim('-')
  .ifBlank { "platform" }

private fun displayNameFromInput(input: String, slug: String): String =
  input.trim().takeIf { it.isNotBlank() } ?: slug.split("-").joinToString(" ") { part ->
    part.replaceFirstChar { character -> character.uppercase() }
  }

private fun promptRequired(state: CliRunState, label: String): String {
  val value = promptOptional(state, label)
  require(value.isNotBlank()) { "Missing required scaffold wizard value: $label." }
  return value
}

private fun promptOptional(state: CliRunState, label: String): String {
  state.liveStdout("$label: ")
  return state.readInputLine()?.trim().orEmpty()
}

private fun normalizeBillSkillName(name: String): String = if (name.startsWith("bill-")) name else "bill-$name"

private inline fun String.ifNotBlank(block: (String) -> Unit) {
  if (isNotBlank()) block(this)
}

private inline fun <T> List<T>.ifNotEmpty(block: (List<T>) -> Unit) {
  if (isNotEmpty()) block(this)
}

private fun parseCommaSeparated(value: String): List<String> =
  value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

private fun runNativeScaffoldPayload(
  payloadPath: String?,
  dryRun: Boolean,
  format: CliFormat,
  state: CliRunState,
  scaffoldService: ScaffoldService,
  transform: (Map<String, *>) -> Map<String, *> = { it },
): CliExecutionResult {
  val payload =
    try {
      transform(readScaffoldPayload(payloadPath, state))
    } catch (error: SkillBillRuntimeException) {
      return errorResult(error.message.orEmpty(), format)
    } catch (error: IllegalArgumentException) {
      return errorResult(error.message.orEmpty(), format)
    }
  return runNativeScaffoldPayload(payload, dryRun, format, scaffoldService)
}

private fun runNativeScaffoldPayload(
  payload: Map<String, *>,
  dryRun: Boolean,
  format: CliFormat,
  scaffoldService: ScaffoldService,
): CliExecutionResult {
  val sessionId = generateScaffoldSessionId()
  val payloadWithRepoRoot = if ((payload["repo_root"] as? String).isNullOrBlank()) {
    payload + ("repo_root" to findRepoRoot().toString())
  } else {
    payload
  }
  // SKILL-52.2 subtask 2: parse the raw map at the CLI adapter boundary and call the typed
  // overload so the application + port surface no longer accepts a raw `Map<String, Any?>`.
  // Materialise the inbound `Map<String, *>` into the `Map<String, Any?>` shape the parser
  // accepts; the keys are already strings — only the value variance widens.
  val typedPayload: Map<String, Any?> = payloadWithRepoRoot.mapValues { (_, value) -> value }
  val result =
    try {
      val request = parseScaffoldCommandRequest(typedPayload)
      scaffoldService.scaffold(request, dryRun = dryRun)
    } catch (error: SkillBillRuntimeException) {
      return errorResult(error.message.orEmpty(), format)
    }
  val created = result.run { createdFiles }.map { path -> path.toString() }
  val presentation =
    mapOf(
      "status" to "ok",
      "session_id" to sessionId,
      "skill_path" to result.skillPath.toString(),
      "dry_run" to dryRun,
      "created_files" to created,
      "manifest_edits" to result.manifestEdits.map { path -> path.toString() },
      "manifest_edit_previews" to result.manifestPreviews.mapKeys { (path, _) -> path.toString() },
      "notes" to result.notes,
    )
  return CliExecutionResult(
    exitCode = 0,
    stdout = CliOutput.emit(presentation, format),
    payload = presentation,
  )
}

private fun createAndFillResult(
  payload: String?,
  interactive: Boolean,
  dryRun: Boolean,
  body: String?,
  bodyFile: String?,
  editor: Boolean,
  format: CliFormat,
  state: CliRunState,
  scaffoldService: ScaffoldService,
  unsupportedScaffoldService: UnsupportedScaffoldService,
): CliExecutionResult = when {
  interactive || payload == null -> unsupportedNativeScaffoldResult(
    unsupportedScaffoldService.retiredUnsupportedMessage(
      "create-and-fill",
      "skill-bill create-and-fill --payload <file> --body-file <file>",
      editor = false,
    ),
    format,
  )
  editor -> unsupportedNativeScaffoldResult(
    "create-and-fill --payload --editor is not supported by the native Kotlin scaffold path yet.",
    format,
  )
  body != null && bodyFile != null -> errorResult("--body and --body-file are mutually exclusive.", format)
  else -> runNativeScaffoldPayload(payload, dryRun, format, state, scaffoldService) { scaffoldPayload ->
    createAndFillScaffoldPayload(scaffoldPayload, body, bodyFile, state)
  }
}

private fun errorResult(message: String, format: CliFormat): CliExecutionResult {
  val presentation =
    mapOf(
      "status" to "error",
      "error" to message,
    )
  return CliExecutionResult(
    exitCode = 1,
    stdout = CliOutput.emit(presentation, format),
    payload = presentation,
  )
}

private fun authoringResult(
  format: CliFormat,
  successExitCode: (Map<String, Any?>) -> Int = { 0 },
  block: () -> Map<String, Any?>,
): CliExecutionResult = try {
  val payload = block()
  CliExecutionResult(
    exitCode = successExitCode(payload),
    stdout = CliOutput.emit(payload, format),
    payload = payload,
  )
} catch (error: SkillBillRuntimeException) {
  errorResult(error.message.orEmpty(), format)
} catch (error: IllegalArgumentException) {
  errorResult(error.message.orEmpty(), format)
}

private fun completeRenderText(
  state: CliRunState,
  repoRoot: Path,
  skillName: String,
  dryRun: Boolean,
  scaffoldService: ScaffoldService,
) = try {
  val rendered = scaffoldService.render(repoRoot, skillName)
  state.completeText(rendered.stdout, rendered.toCliPayload(dryRun))
} catch (error: SkillBillRuntimeException) {
  state.result = errorResult(error.message.orEmpty(), CliFormat.TEXT)
} catch (error: IllegalArgumentException) {
  state.result = errorResult(error.message.orEmpty(), CliFormat.TEXT)
}

private fun ScaffoldRenderResult.toCliPayload(dryRun: Boolean): Map<String, Any?> = mapOf(
  "repo_root" to repoRoot.toString(),
  "skill_name" to skillName,
  "blocks" to blocks.map { block ->
    mapOf(
      "header" to block.header,
      "content" to block.content,
    )
  },
  "dry_run" to dryRun,
)

private fun unsupportedNativeScaffoldResult(message: String, format: CliFormat): CliExecutionResult {
  val presentation =
    mapOf(
      "status" to "unsupported",
      "error" to message,
    )
  return CliExecutionResult(
    exitCode = 1,
    stdout = CliOutput.emit(presentation, format),
    payload = presentation,
  )
}

private fun createAndFillContentPayload(body: String?, bodyFile: String?, state: CliRunState): Map<String, String> {
  val contentBody =
    body ?: bodyFile?.let { path ->
      readCliTextFile(path, state)
    }
  return if (contentBody == null) emptyMap() else mapOf("content_body" to contentBody)
}

private fun createAndFillScaffoldPayload(
  scaffoldPayload: Map<String, *>,
  body: String?,
  bodyFile: String?,
  state: CliRunState,
): Map<String, *> {
  val kind = scaffoldPayload["kind"]?.toString().orEmpty()
  require(kind !in setOf("platform-pack", "add-on")) {
    "create-and-fill can only scaffold one content-managed skill; kind '$kind' is not supported."
  }
  return scaffoldPayload + createAndFillContentPayload(body, bodyFile, state)
}

private fun newAddonPayload(
  platform: String?,
  name: String?,
  body: String?,
  bodyFile: String?,
  consumerSkillDirs: List<String>,
  state: CliRunState,
): Map<String, Any> = buildMap {
  put("scaffold_payload_version", "1.0")
  put("kind", "add-on")
  put("platform", platform.orEmpty())
  put("name", name.orEmpty())
  (body ?: bodyFile?.let { path -> readCliTextFile(path, state) })
    ?.let { addonBody -> put("body", addonBody) }
  if (consumerSkillDirs.isNotEmpty()) {
    put("consumer_skill_dirs", consumerSkillDirs)
  }
}

private fun readCliTextFile(path: String, state: CliRunState): String =
  if (path == "-") state.stdinText.orEmpty() else Path.of(path).toFile().readText()

private fun readScaffoldPayload(payloadPath: String?, state: CliRunState): Map<String, Any?> {
  val payloadText =
    when {
      payloadPath == null -> throw IllegalArgumentException("--payload is required for this command.")
      payloadPath == "-" -> state.stdinText.orEmpty()
      else -> Path.of(payloadPath).toFile().readText()
    }
  val parsed =
    JsonSupport.parseObjectOrNull(payloadText)
      ?: throw IllegalArgumentException("Invalid JSON payload: expected an object.")
  val payload =
    JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
      ?: throw IllegalArgumentException("Invalid JSON payload: expected an object.")
  return payload.toMutableMap().apply {
    this["scaffold_payload_version"] = this["scaffold_payload_version"]?.toString()
  }
}

private fun generateScaffoldSessionId(): String {
  val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
  val suffix = UUID.randomUUID().toString().take(SCAFFOLD_SESSION_SUFFIX_LENGTH)
  return "nss-$date-$suffix"
}

private fun Any?.orEmpty(): String = this as? String ?: ""

private fun findRepoRoot(start: Path = Path.of("").toAbsolutePath().normalize()): Path {
  var current = start
  while (true) {
    val hasSettings = current.resolve("runtime-kotlin/settings.gradle.kts").toFile().isFile
    val hasSkills = current.resolve("skills").toFile().isDirectory
    if (hasSettings && hasSkills) {
      return current
    }
    val parent = current.parent ?: break
    current = parent
  }
  return start
}

@file:Suppress("MaxLineLength", "TooManyFunctions", "LongParameterList", "ReturnCount", "ThrowsCount")

package skillbill.cli

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
import skillbill.application.InstallAgentService
import skillbill.application.InstallService
import skillbill.application.ScaffoldService
import skillbill.application.UnsupportedScaffoldService
import skillbill.contracts.JsonSupport
import skillbill.error.SkillBillRuntimeException
import skillbill.ports.scaffold.model.ScaffoldRenderResult
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
  agentPathCommand: InstallAgentPathCommand,
  detectAgentsCommand: InstallDetectAgentsCommand,
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
        agentPathCommand,
        detectAgentsCommand,
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
  private val unsupportedScaffoldService: UnsupportedScaffoldService,
) : DocumentedCliCommand("new-skill", "Scaffold a new skill from a payload file or interactive prompts.") {
  private val payload by option("--payload", help = "Path to a JSON payload file (or '-' for stdin).")
  private val interactive by option("--interactive", help = "Collect a skill scaffold payload via interactive prompts.")
    .flag(default = false)
  private val dryRun by option("--dry-run", help = "Plan the scaffold and report the operations without touching disk.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    state.result =
      if (interactive) {
        unsupportedNativeScaffoldResult(
          unsupportedScaffoldService.retiredUnsupportedMessage(
            "new-skill --interactive",
            "skill-bill new-skill --payload <file>",
            editor = false,
          ),
          format,
        )
      } else {
        runNativeScaffoldPayload(payload, dryRun, format, state, scaffoldService)
      }
  }
}

@Inject
class NewCommand(
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
  private val unsupportedScaffoldService: UnsupportedScaffoldService,
) : DocumentedCliCommand("new", "Alias for new-skill: scaffold one skill from a payload file or interactive prompts.") {
  private val payload by option("--payload", help = "Path to a JSON payload file (or '-' for stdin).")
  private val interactive by option("--interactive", help = "Collect a skill scaffold payload via interactive prompts.")
    .flag(default = false)
  private val dryRun by option("--dry-run", help = "Plan the scaffold and report the operations without touching disk.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    state.result =
      if (interactive) {
        unsupportedNativeScaffoldResult(
          unsupportedScaffoldService.retiredUnsupportedMessage(
            "new --interactive",
            "skill-bill new --payload <file>",
            editor = false,
          ),
          format,
        )
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
  private val interactive by option("--interactive", help = "Collect a skill scaffold payload via interactive prompts.")
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
  private val platform by option("--platform", help = "Owning platform slug. Required unless --interactive is used.")
  private val name by option(
    "--name",
    help = "Add-on slug (without a bill- prefix). Required unless --interactive is used.",
  )
  private val body by option("--body", help = "Complete markdown body to write to the add-on file.")
  private val bodyFile by option("--body-file", help = "Path to a markdown file to copy into the add-on (or '-').")
  private val consumerSkillDirs by option(
    "--consumer-skill-dir",
    help = "Skill-relative directory to register as an add-on consumer. May be repeated. " +
      "Defaults to the pack baseline code-review skill.",
  ).multiple()
  private val interactive by option("--interactive", help = "Prompt for platform, add-on slug, and markdown content.")
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
            "skill-bill new-addon --platform <platform> --name <name> --body-file <file>",
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
  val result =
    try {
      scaffoldService.scaffold(payloadWithRepoRoot, dryRun = dryRun)
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
  put("body", (body ?: bodyFile?.let { path -> readCliTextFile(path, state) }).orEmpty())
  if (consumerSkillDirs.isNotEmpty()) {
    put("consumer_skill_dirs", consumerSkillDirs)
  }
}

private fun readCliTextFile(path: String, state: CliRunState): String =
  if (path == "-") state.stdinText.orEmpty() else Path.of(path).toFile().readText()

private fun readScaffoldPayload(payloadPath: String?, state: CliRunState): Map<String, Any?> {
  val payloadText =
    when {
      payloadPath == null -> throw IllegalArgumentException("Either --payload or --interactive is required.")
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

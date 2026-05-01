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
import skillbill.contracts.JsonSupport
import skillbill.error.SkillBillRuntimeException
import skillbill.install.InstallOperations
import skillbill.scaffold.AuthoringOperations
import skillbill.scaffold.scaffold
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
  agentPathCommand: InstallAgentPathCommand,
  detectAgentsCommand: InstallDetectAgentsCommand,
  linkSkillCommand: InstallLinkSkillCommand,
) {
  val command: DocumentedNoOpCliCommand =
    object : DocumentedNoOpCliCommand(
      "install",
      "Install-side primitives (agent-path lookup, agent detection, skill symlinking).",
    ) {}
      .subcommands(
        agentPathCommand,
        detectAgentsCommand,
        linkSkillCommand,
      )
}

@Inject
class ListSkillsCommand(
  private val state: CliRunState,
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
        AuthoringOperations.list(Path.of(repoRoot), skillNames)
      }
  }
}

@Inject
class ShowSkillCommand(
  private val state: CliRunState,
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
        AuthoringOperations.show(Path.of(repoRoot), skillName, content)
      }
  }
}

@Inject
class ExplainSkillCommand(
  private val state: CliRunState,
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
        AuthoringOperations.explain(Path.of(repoRoot), skillName)
      }
  }
}

@Inject
class ValidateSkillCommand(
  private val state: CliRunState,
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
        AuthoringOperations.validate(Path.of(repoRoot), skillNames)
      }
  }
}

@Inject
class UpgradeSkillsCommand(
  private val state: CliRunState,
) : WrapperRegenerationCommand("upgrade", state)

@Inject
class RenderSkillsCommand(
  private val state: CliRunState,
) : WrapperRegenerationCommand("render", state)

open class WrapperRegenerationCommand(
  name: String,
  private val state: CliRunState,
) : DocumentedCliCommand(name, "Regenerate scaffold-managed SKILL.md wrappers.") {
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
        AuthoringOperations.upgrade(Path.of(repoRoot), skillNames, validate = !skipValidate)
      }
  }
}

@Inject
class EditSkillCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("edit", "Edit a content-managed skill's authored content.md and regenerate the wrapper.") {
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
            AuthoringOperations.retiredEditorMessage(
              "edit --editor",
              "skill-bill fill $skillName --body-file <file>",
            ),
            format,
          )
        bodyFile != null ->
          authoringResult(format) {
            AuthoringOperations.editWithBodyFile(
              Path.of(repoRoot),
              skillName,
              readCliTextFile(bodyFile.orEmpty(), state),
              section,
            )
          }
        else ->
          unsupportedNativeScaffoldResult(
            AuthoringOperations.retiredInteractiveMessage(
              "edit",
              "skill-bill fill $skillName --body-file <file>",
            ),
            format,
          )
      }
  }
}

@Inject
class FillSkillCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("fill", "Write authored content into content.md, regenerate the wrapper, and validate it.") {
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
            AuthoringOperations.fill(
              Path.of(repoRoot),
              skillName,
              body ?: readCliTextFile(bodyFile.orEmpty(), state),
              section,
            )
          }
      }
  }
}

@Inject
class NewSkillCommand(
  private val state: CliRunState,
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
          AuthoringOperations.retiredInteractiveMessage(
            "new-skill --interactive",
            "skill-bill new-skill --payload <file>",
          ),
          format,
        )
      } else {
        runNativeScaffoldPayload(payload, dryRun, format, state)
      }
  }
}

@Inject
class NewCommand(
  private val state: CliRunState,
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
          AuthoringOperations.retiredInteractiveMessage(
            "new --interactive",
            "skill-bill new --payload <file>",
          ),
          format,
        )
      } else {
        runNativeScaffoldPayload(payload, dryRun, format, state)
      }
  }
}

@Inject
class CreateAndFillCommand(
  private val state: CliRunState,
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
  private val bodyFile by option("--body-file", help = "Optional file path (or '-') to read the authored body from.")
  private val editor by option("--editor", help = "Open the scaffolded content.md in \$VISUAL or \$EDITOR.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    state.result = createAndFillResult(payload, interactive, dryRun, body, bodyFile, editor, format, state)
  }
}

@Inject
class NewAddonCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("new-addon", "Create a governed add-on file inside an existing platform pack.") {
  private val platform by option("--platform", help = "Owning platform slug. Required unless --interactive is used.")
  private val name by option(
    "--name",
    help = "Add-on slug (without a bill- prefix). Required unless --interactive is used.",
  )
  private val body by option("--body", help = "Complete markdown body to write to the add-on file.")
  private val bodyFile by option("--body-file", help = "Path to a markdown file to copy into the add-on (or '-').")
  private val interactive by option("--interactive", help = "Prompt for platform, add-on slug, and markdown content.")
    .flag(default = false)
  private val dryRun by option("--dry-run", help = "Plan the scaffold and report the operations without touching disk.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    state.result =
      if (interactive) {
        unsupportedNativeScaffoldResult(
          AuthoringOperations.retiredInteractiveMessage(
            "new-addon --interactive",
            "skill-bill new-addon --platform <platform> --name <name> --body-file <file>",
          ),
          format,
        )
      } else if (body != null && bodyFile != null) {
        errorResult("--body and --body-file are mutually exclusive.", format)
      } else {
        runNativeScaffoldPayload(newAddonPayload(platform, name, body, bodyFile, state), dryRun, format)
      }
  }
}

@Inject
class InstallAgentPathCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("agent-path", "Print the canonical install directory for a given agent.") {
  private val agent by argument(help = "Agent name.")

  override fun run() {
    val path = InstallOperations.agentPath(agent, state.userHome)
    state.result = CliExecutionResult(exitCode = 0, stdout = "$path\n")
  }
}

@Inject
class InstallDetectAgentsCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("detect-agents", "List detected agents as 'name\\tpath' lines.") {
  override fun run() {
    val output =
      InstallOperations.detectAgentTargets(state.userHome)
        .joinToString(separator = "") { target -> "${target.name}\t${target.path}\n" }
    state.result = CliExecutionResult(exitCode = 0, stdout = output)
  }
}

@Inject
class InstallLinkSkillCommand(
  private val state: CliRunState,
) : DocumentedCliCommand(
  "link-skill",
  "Symlink a skill DIRECTORY into an agent's install directory.",
) {
  private val source by option("--source", help = "Skill directory to install.").required()
  private val targetDir by option("--target-dir", help = "Target install directory.").required()
  private val agent by option("--agent", help = "Optional agent name to label the install.").default("")

  override fun run() {
    InstallOperations.linkSkill(
      source = Path.of(source),
      targetDir = Path.of(targetDir),
      agent = agent,
    )
    state.result = CliExecutionResult(exitCode = 0, stdout = "")
  }
}

private fun runNativeScaffoldPayload(
  payloadPath: String?,
  dryRun: Boolean,
  format: CliFormat,
  state: CliRunState,
  transform: (Map<String, *>) -> Map<String, *> = { it },
): CliExecutionResult = runNativeScaffoldPayload(transform(readScaffoldPayload(payloadPath, state)), dryRun, format)

private fun runNativeScaffoldPayload(payload: Map<String, *>, dryRun: Boolean, format: CliFormat): CliExecutionResult {
  val sessionId = generateScaffoldSessionId()
  val repoRoot = findRepoRoot()
  val result =
    try {
      scaffold(payload + ("repo_root" to repoRoot.toString()), dryRun = dryRun)
    } catch (error: SkillBillRuntimeException) {
      return errorResult(error.message.orEmpty(), format)
    }
  val presentation =
    mapOf(
      "status" to "ok",
      "session_id" to sessionId,
      "skill_path" to result.skillPath.toString(),
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
): CliExecutionResult = when {
  interactive || payload == null -> unsupportedNativeScaffoldResult(
    AuthoringOperations.retiredInteractiveMessage(
      "create-and-fill",
      "skill-bill create-and-fill --payload <file> --body-file <file>",
    ),
    format,
  )
  editor -> unsupportedNativeScaffoldResult(
    "create-and-fill --payload --editor is not supported by the native Kotlin scaffold path yet.",
    format,
  )
  body != null && bodyFile != null -> errorResult("--body and --body-file are mutually exclusive.", format)
  else -> runNativeScaffoldPayload(payload, dryRun, format, state) { scaffoldPayload ->
    scaffoldPayload + createAndFillContentPayload(body, bodyFile, state)
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

private fun newAddonPayload(
  platform: String?,
  name: String?,
  body: String?,
  bodyFile: String?,
  state: CliRunState,
): Map<String, String> = mapOf(
  "scaffold_payload_version" to "1.0",
  "kind" to "add-on",
  "platform" to platform.orEmpty(),
  "name" to name.orEmpty(),
  "body" to (body ?: bodyFile?.let { path -> readCliTextFile(path, state) }).orEmpty(),
)

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
    if (current.resolve("skill_bill").toFile().isDirectory && current.resolve("runtime-kotlin").toFile().isDirectory) {
      return current
    }
    val parent = current.parent ?: break
    current = parent
  }
  return start
}

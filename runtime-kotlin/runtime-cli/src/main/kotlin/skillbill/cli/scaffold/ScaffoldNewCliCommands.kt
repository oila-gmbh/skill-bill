package skillbill.cli.scaffold

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.application.install.ExternalAddonOverlayService
import skillbill.application.scaffold.InstallAgentService
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.application.scaffold.ScaffoldService
import skillbill.application.scaffold.UnsupportedScaffoldService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.formatOption

@Inject
class NewSkillCommand(
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
  private val scaffoldCatalogService: ScaffoldCatalogService,
  private val installAgentService: InstallAgentService,
  private val externalAddonOverlayService: ExternalAddonOverlayService,
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
    val runArgs = nativeScaffoldRunArgs(dryRun, format, state, scaffoldService, externalAddonOverlayService)
    state.result =
      if (assisted && payload != null) {
        errorResult("--assisted cannot be combined with --payload.", format)
      } else if (assisted) {
        runNativeAssistedScaffoldWizard(
          AssistedScaffoldWizardArgs(
            run = runArgs,
            scaffoldCatalogService = scaffoldCatalogService,
            installAgentService = installAgentService,
          ),
        )
      } else if (interactive || payload == null) {
        runNativeScaffoldWizard(
          ScaffoldWizardArgs(
            run = runArgs,
            scaffoldCatalogService = scaffoldCatalogService,
          ),
        )
      } else {
        runNativeScaffoldPayload(
          NativeScaffoldPayloadPathArgs(payloadPath = payload, run = runArgs),
        )
      }
  }
}

@Inject
class NewCommand(
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
  private val scaffoldCatalogService: ScaffoldCatalogService,
  private val installAgentService: InstallAgentService,
  private val externalAddonOverlayService: ExternalAddonOverlayService,
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
    val runArgs = nativeScaffoldRunArgs(dryRun, format, state, scaffoldService, externalAddonOverlayService)
    state.result =
      if (assisted && payload != null) {
        errorResult("--assisted cannot be combined with --payload.", format)
      } else if (assisted) {
        runNativeAssistedScaffoldWizard(
          AssistedScaffoldWizardArgs(
            run = runArgs,
            scaffoldCatalogService = scaffoldCatalogService,
            installAgentService = installAgentService,
          ),
        )
      } else if (interactive || payload == null) {
        runNativeScaffoldWizard(
          ScaffoldWizardArgs(
            run = runArgs,
            scaffoldCatalogService = scaffoldCatalogService,
          ),
        )
      } else {
        runNativeScaffoldPayload(
          NativeScaffoldPayloadPathArgs(payloadPath = payload, run = runArgs),
        )
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
        CreateAndFillArgs(
          content = CreateAndFillContentArgs(
            payload = payload,
            interactive = interactive,
            body = body,
            bodyFile = bodyFile,
            editor = editor,
          ),
          dryRun = dryRun,
          format = format,
          state = state,
          scaffoldService = scaffoldService,
          unsupportedScaffoldService = unsupportedScaffoldService,
        ),
      )
  }
}

@Inject
class NewAddonCommand(
  private val state: CliRunState,
  private val scaffoldService: ScaffoldService,
  private val unsupportedScaffoldService: UnsupportedScaffoldService,
  private val externalAddonOverlayService: ExternalAddonOverlayService,
) : DocumentedCliCommand(
  "new-addon",
  "Create a governed add-on file inside an existing platform pack or external add-on source.",
) {
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
  private val addonLocationPath by option(
    "--addon-location-path",
    help = "Optional external add-on source directory. When set, writes <name>.md and addon-manifest.yaml there.",
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
          newAddonPayload(
            NewAddonPayloadArgs(
              platform = platform,
              name = name,
              body = body,
              bodyFile = bodyFile,
              addonLocationPath = addonLocationPath,
              consumerSkillDirs = consumerSkillDirs,
              state = state,
            ),
          ),
          nativeScaffoldRunArgs(dryRun, format, state, scaffoldService, externalAddonOverlayService),
        )
      }
  }
}

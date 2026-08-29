package skillbill.cli.scaffold

import skillbill.application.install.ExternalAddonOverlayService
import skillbill.application.scaffold.InstallAgentService
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.application.scaffold.ScaffoldService
import skillbill.cli.core.CliRunState
import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliFormat
import skillbill.error.SkillBillRuntimeException
import skillbill.install.model.InstallAgent
import skillbill.scaffold.model.command.isRetiredPartialScaffoldCommandKindAlias
import skillbill.scaffold.model.command.rejectRetiredPartialScaffoldCommandKind

internal fun runNativeScaffoldWizard(
  dryRun: Boolean,
  format: CliFormat,
  state: CliRunState,
  scaffoldService: ScaffoldService,
  scaffoldCatalogService: ScaffoldCatalogService,
  externalAddonOverlayService: ExternalAddonOverlayService,
): CliExecutionResult {
  val payload =
    try {
      collectScaffoldWizardPayload(state, scaffoldCatalogService)
    } catch (error: SkillBillRuntimeException) {
      return errorResult(error.message.orEmpty(), format)
    } catch (error: IllegalArgumentException) {
      return errorResult(error.message.orEmpty(), format)
    }
  return runNativeScaffoldPayload(payload, dryRun, format, scaffoldService, state, externalAddonOverlayService)
}

internal fun runNativeAssistedScaffoldWizard(
  dryRun: Boolean,
  format: CliFormat,
  state: CliRunState,
  scaffoldService: ScaffoldService,
  scaffoldCatalogService: ScaffoldCatalogService,
  installAgentService: InstallAgentService,
  externalAddonOverlayService: ExternalAddonOverlayService,
): CliExecutionResult {
  val payload =
    try {
      collectAssistedScaffoldWizardPayload(state, scaffoldCatalogService, installAgentService)
    } catch (error: SkillBillRuntimeException) {
      return errorResult(error.message.orEmpty(), format)
    } catch (error: IllegalArgumentException) {
      return errorResult(error.message.orEmpty(), format)
    }
  return runNativeScaffoldPayload(payload, dryRun, format, scaffoldService, state, externalAddonOverlayService)
}

internal fun collectAssistedScaffoldWizardPayload(
  state: CliRunState,
  scaffoldCatalogService: ScaffoldCatalogService,
  installAgentService: InstallAgentService,
): Map<String, Any?> {
  state.liveStdout(
    "Skill Bill assisted scaffold wizard\n" +
      "Kind: 1 horizontal, 2 platform-pack, 3 add-on, 4 agent-addon\n\n",
  )
  val kind = normalizeWizardKind(promptRequired(state, "Kind"))
  val agent =
    promptAssistedAgent(
      state,
      installAgentService.detectAgentTargets(state.userHome, state.environment).map { target -> target.name },
    )
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

internal fun collectScaffoldWizardPayload(
  state: CliRunState,
  scaffoldCatalogService: ScaffoldCatalogService,
): Map<String, Any?> {
  state.liveStdout(
    "Skill Bill scaffold wizard\n" +
      "Kind: 1 horizontal, 2 platform-pack, 3 add-on, 4 agent-addon\n\n",
  )
  return when (val kind = normalizeWizardKind(promptRequired(state, "Kind"))) {
    "horizontal" -> horizontalWizardPayload(state)
    "platform-pack" -> platformPackWizardPayload(state, scaffoldCatalogService.platformPackPresets())
    "add-on" -> addOnWizardPayload(state)
    "agent-addon" -> agentAddonWizardPayload(state)
    else -> throw IllegalArgumentException("Unsupported scaffold wizard kind '$kind'.")
  }
}

internal fun horizontalWizardPayload(state: CliRunState): Map<String, Any?> = buildMap {
  putScaffoldBase("horizontal")
  put("name", normalizeBillSkillName(promptRequired(state, "Skill name")))
  promptOptional(state, "Description").ifNotBlank { description -> put("description", description) }
}

internal fun agentAddonWizardPayload(state: CliRunState): Map<String, Any?> = buildMap {
  putScaffoldBase("agent-addon")
  put("slug", promptRequired(state, "Agent add-on slug"))
  put("description", promptRequired(state, "Description"))
  state.liveStdout("Supported agents: ${InstallAgent.supportedIds.joinToString(", ")}\n")
  put("agent_ids", requiredCommaSeparated(state, "Agent IDs (comma-separated)"))
  put("consumers", requiredCommaSeparated(state, "Consumers (comma-separated, supported: bill-feature)"))
}

internal fun platformPackWizardPayload(
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

internal fun assistedPlatformPackWizardPayload(
  state: CliRunState,
  platformPackPresets: Map<String, String>,
): Map<String, Any?> {
  val platformInput = promptRequired(state, "Language or platform")
  return assistedPlatformPackPayload(platformInput, platformPackPresets)
}

internal fun assistedPlatformPackPayload(
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

internal fun addOnWizardPayload(state: CliRunState): Map<String, Any?> = buildMap {
  putScaffoldBase("add-on")
  put("platform", promptRequired(state, "Platform slug"))
  put("name", promptRequired(state, "Add-on name"))
  when (val mode = normalizeAddOnLocationMode(promptDefault(state, "Add-on source (native/external)", "native"))) {
    "native" -> Unit
    "external" -> put("addon_location_path", promptRequired(state, "External add-on source path"))
  }
  promptOptional(state, "Description").ifNotBlank { description -> put("description", description) }
}

internal fun MutableMap<String, Any?>.putScaffoldBase(kind: String) {
  put("scaffold_payload_version", "1.0")
  put("kind", kind)
}

internal fun normalizeWizardKind(value: String): String = when (value.trim().lowercase()) {
  "1", "horizontal", "skill" -> "horizontal"
  "2", "platform", "platform-pack", "pack" -> "platform-pack"
  "3", "add-on", "addon" -> "add-on"
  "4", "agent-addon", "agent-addon-skill" -> "agent-addon"
  else -> if (isRetiredPartialScaffoldCommandKindAlias(value)) {
    rejectRetiredPartialScaffoldCommandKind(value)
  } else {
    value
  }
}

internal fun requiredCommaSeparated(state: CliRunState, label: String): List<String> =
  parseCommaSeparated(promptRequired(state, label)).also { values ->
    require(values.isNotEmpty()) { "Missing required scaffold wizard value: $label." }
  }

internal fun normalizeAddOnLocationMode(value: String): String = when (value.trim().lowercase()) {
  "1", "native", "pack", "pack-owned" -> "native"
  "2", "external" -> "external"
  else -> throw IllegalArgumentException("Unsupported add-on source '$value'. Use native or external.")
}

internal fun promptDefault(state: CliRunState, label: String, default: String): String {
  val value = promptOptional(state, label)
  return value.ifBlank { default }
}

internal fun promptRoutingSignals(state: CliRunState, platform: String, hasPreset: Boolean): List<String> {
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

internal fun promptAssistedAgent(state: CliRunState, detectedAgents: List<String>): String {
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

internal data class AssistedPlatformProfile(
  val slug: String,
  val displayName: String,
  val strongSignals: List<String>,
)

internal fun assistedPlatformProfile(input: String): AssistedPlatformProfile {
  val key = languageLookupKey(input)
  return assistedPlatformProfiles()[key] ?: fallbackAssistedPlatformProfile(input)
}

internal fun assistedPlatformProfiles(): Map<String, AssistedPlatformProfile> = buildMap {
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

internal fun MutableMap<String, AssistedPlatformProfile>.putProfile(
  slug: String,
  displayName: String,
  strongSignals: List<String>,
  vararg aliases: String,
) {
  val profile = AssistedPlatformProfile(slug = slug, displayName = displayName, strongSignals = strongSignals)
  aliases.forEach { alias -> put(languageLookupKey(alias), profile) }
}

internal fun fallbackAssistedPlatformProfile(input: String): AssistedPlatformProfile {
  val slug = platformSlugFromInput(input)
  val displayName = displayNameFromInput(input, slug)
  return AssistedPlatformProfile(
    slug = slug,
    displayName = displayName,
    strongSignals = listOf(".$slug", "$slug/"),
  )
}

internal fun languageLookupKey(value: String): String =
  value.trim().lowercase().filter { character -> character.isLetterOrDigit() || character == '#' || character == '+' }

internal fun platformSlugFromInput(value: String): String = value.trim()
  .lowercase()
  .replace(Regex("[^a-z0-9]+"), "-")
  .trim('-')
  .ifBlank { "platform" }

internal fun displayNameFromInput(input: String, slug: String): String =
  input.trim().takeIf { it.isNotBlank() } ?: slug.split("-").joinToString(" ") { part ->
    part.replaceFirstChar { character -> character.uppercase() }
  }

internal fun promptRequired(state: CliRunState, label: String): String {
  val value = promptOptional(state, label)
  require(value.isNotBlank()) { "Missing required scaffold wizard value: $label." }
  return value
}

internal fun promptOptional(state: CliRunState, label: String): String {
  state.liveStdout("$label: ")
  return state.readInputLine()?.trim().orEmpty()
}

internal fun normalizeBillSkillName(name: String): String = if (name.startsWith("bill-")) name else "bill-$name"

private inline fun String.ifNotBlank(block: (String) -> Unit) {
  if (isNotBlank()) block(this)
}

private inline fun <T> List<T>.ifNotEmpty(block: (List<T>) -> Unit) {
  if (isNotEmpty()) block(this)
}

internal fun parseCommaSeparated(value: String): List<String> =
  value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

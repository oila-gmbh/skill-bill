package skillbill.cli.scaffold

import skillbill.cli.core.CliRunState
import skillbill.scaffold.model.command.isRetiredPartialScaffoldCommandKindAlias
import skillbill.scaffold.model.command.rejectRetiredPartialScaffoldCommandKind

internal fun addOnWizardPayload(state: CliRunState): Map<String, Any?> = buildMap {
  putScaffoldBase("add-on")
  put("platform", promptRequired(state, "Platform slug"))
  put("name", promptRequired(state, "Add-on name"))
  when (normalizeAddOnLocationMode(promptDefault(state, "Add-on source (native/external)", "native"))) {
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

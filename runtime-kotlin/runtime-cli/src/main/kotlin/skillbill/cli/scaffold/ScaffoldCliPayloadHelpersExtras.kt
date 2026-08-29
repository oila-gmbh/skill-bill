package skillbill.cli.scaffold

import skillbill.cli.core.CliRunState
import skillbill.contracts.JsonSupport
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

internal fun createAndFillContentPayload(body: String?, bodyFile: String?, state: CliRunState): Map<String, String> {
  val contentBody =
    body ?: bodyFile?.let { path ->
      readCliTextFile(path, state)
    }
  return if (contentBody == null) emptyMap() else mapOf("content_body" to contentBody)
}

internal fun createAndFillScaffoldPayload(
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

internal fun newAddonPayload(args: NewAddonPayloadArgs): Map<String, Any> = buildMap {
  put("scaffold_payload_version", "1.0")
  put("kind", "add-on")
  put("platform", args.platform.orEmpty())
  put("name", args.name.orEmpty())
  (args.body ?: args.bodyFile?.let { path -> readCliTextFile(path, args.state) })
    ?.let { addonBody -> put("body", addonBody) }
  args.addonLocationPath?.takeIf { it.isNotBlank() }?.let { path -> put("addon_location_path", path) }
  if (args.consumerSkillDirs.isNotEmpty()) {
    put("consumer_skill_dirs", args.consumerSkillDirs)
  }
}

internal fun readCliTextFile(path: String, state: CliRunState): String =
  if (path == "-") state.stdinText.orEmpty() else Path.of(path).toFile().readText()

internal fun readScaffoldPayload(payloadPath: String?, state: CliRunState): Map<String, Any?> {
  val payloadText = readScaffoldPayloadText(payloadPath, state)
  val payload = parseScaffoldPayloadObject(payloadText).toMutableMap()
  payload["scaffold_payload_version"] = payload["scaffold_payload_version"]?.toString()
  return payload
}

internal fun readScaffoldPayloadText(payloadPath: String?, state: CliRunState): String = when {
  payloadPath == null -> throw IllegalArgumentException("--payload is required for this command.")
  payloadPath == "-" -> state.stdinText.orEmpty()
  else -> Path.of(payloadPath).toFile().readText()
}

internal fun parseScaffoldPayloadObject(payloadText: String): Map<String, Any?> {
  val parsed = JsonSupport.parseObjectOrNull(payloadText)
    ?: throw IllegalArgumentException("Invalid JSON payload: expected an object.")
  return JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
    ?: throw IllegalArgumentException("Invalid JSON payload: expected an object.")
}

internal fun generateScaffoldSessionId(): String {
  val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
  val suffix = UUID.randomUUID().toString().take(SCAFFOLD_SESSION_SUFFIX_LENGTH)
  return "nss-$date-$suffix"
}

internal fun Any?.orEmpty(): String = this as? String ?: ""

internal fun findRepoRoot(start: Path = Path.of("").toAbsolutePath().normalize()): Path {
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

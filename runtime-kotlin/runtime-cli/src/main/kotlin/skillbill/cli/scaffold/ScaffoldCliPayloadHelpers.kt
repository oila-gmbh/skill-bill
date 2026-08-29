package skillbill.cli.scaffold

import skillbill.application.install.ExternalAddonOverlayService
import skillbill.application.scaffold.UnsupportedScaffoldService
import skillbill.application.scaffold.ScaffoldService
import skillbill.cli.core.CliOutput
import skillbill.cli.core.CliRunState
import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliFormat
import skillbill.contracts.JsonSupport
import skillbill.error.SkillBillRuntimeException
import skillbill.install.model.ExternalAddonSource
import skillbill.ports.scaffold.model.ScaffoldRenderResult
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

internal const val SCAFFOLD_SESSION_SUFFIX_LENGTH = 4

internal fun runNativeScaffoldPayload(
  payloadPath: String?,
  dryRun: Boolean,
  format: CliFormat,
  state: CliRunState,
  scaffoldService: ScaffoldService,
  externalAddonOverlayService: ExternalAddonOverlayService? = null,
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
  return runNativeScaffoldPayload(payload, dryRun, format, scaffoldService, state, externalAddonOverlayService)
}

internal fun runNativeScaffoldPayload(
  payload: Map<String, *>,
  dryRun: Boolean,
  format: CliFormat,
  scaffoldService: ScaffoldService,
  state: CliRunState,
  externalAddonOverlayService: ExternalAddonOverlayService? = null,
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
      val scaffoldResult = scaffoldService.scaffold(request, dryRun = dryRun)
      registerExternalAddonSourceAfterSuccess(request, dryRun, state, externalAddonOverlayService)
      scaffoldResult
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

internal fun registerExternalAddonSourceAfterSuccess(
  request: ScaffoldCommandRequest,
  dryRun: Boolean,
  state: CliRunState,
  externalAddonOverlayService: ExternalAddonOverlayService?,
) {
  if (externalAddonOverlayService == null) return
  if (dryRun) return
  val addOn = request as? ScaffoldCommandRequest.AddOn ?: return
  val sourcePath = addOn.addonLocationPath?.takeIf(String::isNotBlank) ?: return
  externalAddonOverlayService.registerSource(
    home = state.userHome,
    source = ExternalAddonSource(Path.of(sourcePath), addOn.platform),
    environment = state.environment,
  )
}

internal fun createAndFillResult(
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

internal fun errorResult(message: String, format: CliFormat): CliExecutionResult {
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

internal fun authoringResult(
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

internal fun completeRenderText(
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

internal fun ScaffoldRenderResult.toCliPayload(dryRun: Boolean): Map<String, Any?> = mapOf(
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

internal fun unsupportedNativeScaffoldResult(message: String, format: CliFormat): CliExecutionResult {
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

internal fun newAddonPayload(
  platform: String?,
  name: String?,
  body: String?,
  bodyFile: String?,
  addonLocationPath: String?,
  consumerSkillDirs: List<String>,
  state: CliRunState,
): Map<String, Any> = buildMap {
  put("scaffold_payload_version", "1.0")
  put("kind", "add-on")
  put("platform", platform.orEmpty())
  put("name", name.orEmpty())
  (body ?: bodyFile?.let { path -> readCliTextFile(path, state) })
    ?.let { addonBody -> put("body", addonBody) }
  addonLocationPath?.takeIf { it.isNotBlank() }?.let { path -> put("addon_location_path", path) }
  if (consumerSkillDirs.isNotEmpty()) {
    put("consumer_skill_dirs", consumerSkillDirs)
  }
}

internal fun readCliTextFile(path: String, state: CliRunState): String =
  if (path == "-") state.stdinText.orEmpty() else Path.of(path).toFile().readText()

internal fun readScaffoldPayload(payloadPath: String?, state: CliRunState): Map<String, Any?> {
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

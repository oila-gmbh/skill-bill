package skillbill.cli.scaffold

import skillbill.application.install.ExternalAddonOverlayService
import skillbill.application.scaffold.ScaffoldService
import skillbill.cli.kernel.CliOutput
import skillbill.cli.kernel.CliRunState
import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliFormat
import skillbill.cli.model.CliRunInputs
import skillbill.error.SkillBillRuntimeException
import skillbill.install.model.ExternalAddonSource
import skillbill.ports.scaffold.model.ScaffoldRenderResult
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import java.nio.file.Path

internal fun runNativeScaffoldPayload(args: NativeScaffoldPayloadPathArgs): CliExecutionResult {
  val payload =
    try {
      args.transform(readScaffoldPayload(args.payloadPath, args.run.inputs))
    } catch (error: SkillBillRuntimeException) {
      return errorResult(error.message.orEmpty(), args.run.format)
    } catch (error: IllegalArgumentException) {
      return errorResult(error.message.orEmpty(), args.run.format)
    }
  return runNativeScaffoldPayload(payload, args.run)
}

internal fun runNativeScaffoldPayload(payload: Map<String, *>, run: NativeScaffoldRunArgs): CliExecutionResult {
  val dryRun = run.dryRun
  val format = run.format
  val inputs = run.inputs
  val scaffoldService = run.scaffoldService
  val externalAddonOverlayService = run.externalAddonOverlayService
  val sessionId = generateScaffoldSessionId(run.clock)
  val payloadWithRepoRoot = if ((payload["repo_root"] as? String).isNullOrBlank()) {
    payload + ("repo_root" to findRepoRoot(inputs.repositoryRoot).toString())
  } else {
    payload
  }
  val typedPayload: Map<String, Any?> = payloadWithRepoRoot.mapValues { (_, value) -> value }
  val result =
    try {
      val request = parseScaffoldCommandRequest(typedPayload)
      val scaffoldResult = scaffoldService.scaffold(request, dryRun = dryRun)
      registerExternalAddonSourceAfterSuccess(request, dryRun, inputs, externalAddonOverlayService)
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

internal fun createAndFillResult(args: CreateAndFillArgs): CliExecutionResult {
  val content = args.content
  val format = args.format
  return when {
    content.interactive || content.payload == null -> unsupportedNativeScaffoldResult(
      args.unsupportedScaffoldService.retiredUnsupportedMessage(
        "create-and-fill",
        "skill-bill create-and-fill --payload <file> --body-file <file>",
        editor = false,
      ),
      format,
    )
    content.editor -> unsupportedNativeScaffoldResult(
      "create-and-fill --payload --editor is not supported by the native Kotlin scaffold path yet.",
      format,
    )
    content.body != null && content.bodyFile != null ->
      errorResult("--body and --body-file are mutually exclusive.", format)
    else -> runNativeScaffoldPayload(
      NativeScaffoldPayloadPathArgs(
        payloadPath = content.payload,
        run = NativeScaffoldRunArgs(
          dryRun = args.dryRun,
          format = format,
          state = args.state,
          inputs = args.inputs,
          clock = args.clock,
          scaffoldService = args.scaffoldService,
        ),
        transform = { scaffoldPayload ->
          createAndFillScaffoldPayload(scaffoldPayload, content.body, content.bodyFile, args.inputs)
        },
      ),
    )
  }
}

internal const val SCAFFOLD_SESSION_SUFFIX_LENGTH = 4

internal fun registerExternalAddonSourceAfterSuccess(
  request: ScaffoldCommandRequest,
  dryRun: Boolean,
  inputs: CliRunInputs,
  externalAddonOverlayService: ExternalAddonOverlayService?,
) {
  if (externalAddonOverlayService == null) return
  if (dryRun) return
  val addOn = request as? ScaffoldCommandRequest.AddOn ?: return
  val sourcePath = addOn.addonLocationPath?.takeIf(String::isNotBlank) ?: return
  externalAddonOverlayService.registerSource(
    home = inputs.userHome,
    source = ExternalAddonSource(Path.of(sourcePath), addOn.platform),
    environment = inputs.environment,
  )
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

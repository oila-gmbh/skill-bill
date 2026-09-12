package skillbill.infrastructure.fs.launcher.agentrun

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.infrastructure.fs.launcher.process.AgentRunProcessEnvironmentFields
import skillbill.infrastructure.fs.launcher.process.AgentRunProcessLaunchFields
import skillbill.infrastructure.fs.launcher.process.AgentRunProcessProbeFields
import skillbill.infrastructure.fs.launcher.process.AgentRunProcessRequest
import skillbill.infrastructure.fs.launcher.process.AgentRunProcessReviewFields
import skillbill.infrastructure.fs.launcher.process.AgentRunProcessRunner
import skillbill.infrastructure.fs.launcher.process.AgentRunProcessTimingFields
import skillbill.infrastructure.fs.launcher.review.CursorReviewStreamMalformedError
import skillbill.install.model.AgentLauncherCli
import skillbill.install.model.InstallAgent
import skillbill.install.model.agentLauncherUnavailableMessage
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Path
import java.security.MessageDigest

interface AgentRunAdapter {
  val agent: InstallAgent
  fun launch(request: SkillRunRequest): AgentRunLaunchFacts
}

internal sealed interface LauncherResolution {
  data class Resolved(val command: List<String>) : LauncherResolution
  data class Missing(val message: String) : LauncherResolution
}

class ProcessAgentRunAdapter(
  override val agent: InstallAgent,
  private val commandBuilder: AgentRunCommandBuilder,
  private val processRunner: AgentRunProcessRunner,
  private val executableLookup: ExecutableLookup = PathExecutableLookup(),
) : AgentRunAdapter {
  override fun launch(request: SkillRunRequest): AgentRunLaunchFacts {
    val built = commandBuilder.build(request)
    val command = when (val resolution = resolveLauncherExecutable(built.command, commandBuilder.launcherCli)) {
      is LauncherResolution.Resolved -> built.copy(command = resolution.command)
      is LauncherResolution.Missing -> return unavailableLauncherFacts(request, built, resolution.message)
    }
    val decoder = command.outputDecoder ?: commandBuilder.outputDecoder
    val capture = request.auditRepairProviderSessionSink?.let {
      ProviderSessionOutputSink(decoder, it, request.outputSink)
    }
    val result = processRunner.run(processRequest(command, request, capture ?: request.outputSink))
    capture?.finish()
    val providerSessionId = capture?.requireSessionId()
    val decoded = runCatching { decoder.decode(result.stdout) }.getOrElse { error ->
      if (!decoder.undecodable(error)) throw error
      DecodedAgentRunOutput(text = "", rawOutputPreview = result.stdout.take(RAW_OUTPUT_PREVIEW_MAX_CHARS))
    }
    require(result.spawnFailed != result.processStarted) {
      "AgentRunProcessRunner result must report exactly one of spawnFailed/processStarted; got " +
        "spawnFailed=${result.spawnFailed}, processStarted=${result.processStarted}."
    }
    val normalizedStdout = decoded.text
    val decodedBodyBytes = if (normalizedStdout == result.stdout) {
      result.stdoutBytes
    } else {
      normalizedStdout.encodeToByteArray()
    }
    return AgentRunLaunchFacts(
      agent = agent,
      exitStatus = result.exitStatus,
      stdout = normalizedStdout,
      stdoutBytes = decodedBodyBytes,
      stderr = result.stderr,
      timedOut = result.timedOut,
      interrupted = result.interrupted,
      spawnFailed = result.spawnFailed,
      liveness = result.liveness,
      processStarted = result.processStarted,
      mcpStartupObserved = result.mcpStartupObserved,
      stdoutTruncated = result.stdoutTruncated,
      stdoutByteSize = if (result.stdoutTruncated) result.stdoutByteSize else decodedBodyBytes.size.toLong(),
      stdoutSha256 = if (result.stdoutTruncated) result.stdoutSha256 else sha256(decodedBodyBytes),
      childSessionPath = command.workingDirectory.toString(),
      childSessionId = childSessionId(agent, request, command.workingDirectory),
      providerSessionId = providerSessionId ?: decoded.providerSessionId,
      assistantEventCount = decoded.assistantEventCount,
      rawOutputPreview = decoded.rawOutputPreview,
    )
  }

  private fun resolveLauncherExecutable(command: List<String>, launcher: AgentLauncherCli): LauncherResolution {
    val requested = command.firstOrNull()
    return when {
      requested == null -> LauncherResolution.Missing("Agent '${agent.id}' produced an empty launch command.")
      executableLookup.onPath(requested) -> LauncherResolution.Resolved(command)
      requested !in launcher.executables ->
        LauncherResolution.Missing("Agent '${agent.id}' cannot be launched: '$requested' is not on PATH.")
      else -> resolveDeclaredAlternate(requested, command, launcher)
    }
  }

  private fun resolveDeclaredAlternate(
    requested: String,
    command: List<String>,
    launcher: AgentLauncherCli,
  ): LauncherResolution {
    val alternate = launcher.executables
      .firstOrNull { candidate -> candidate != requested && executableLookup.onPath(candidate) }
      ?: return LauncherResolution.Missing(agentLauncherUnavailableMessage(agent, requested, launcher.installHint))
    return LauncherResolution.Resolved(listOf(alternate) + command.drop(1))
  }

  private fun unavailableLauncherFacts(request: SkillRunRequest, command: AgentRunCommand, message: String) =
    AgentRunLaunchFacts(
      agent = agent,
      exitStatus = null,
      stdout = "",
      stderr = message,
      timedOut = false,
      spawnFailed = true,
      childSessionPath = command.workingDirectory.toString(),
      childSessionId = childSessionId(agent, request, command.workingDirectory),
    )

  private fun processRequest(command: AgentRunCommand, request: SkillRunRequest, outputSink: AgentRunOutputSink) =
    AgentRunProcessRequest(
      launch = AgentRunProcessLaunchFields(
        command = command.command,
        workingDirectory = command.workingDirectory,
        stdinText = command.stdinText,
        outputSink = outputSink,
      ),
      timing = AgentRunProcessTimingFields(
        timeout = command.timeout,
        progressIdleTimeout = request.progressIdleTimeout,
        operationDeadline = request.timeout,
      ),
      probes = AgentRunProcessProbeFields(
        progressProbe = request.progressProbe,
        declaredProgressProbe = request.declaredProgressProbe,
        mcpStartupProbe = request.mcpStartupProbe,
        progressEmitter = request.progressEmitter,
        activityProbe = WorktreeActivityProbe(command.workingDirectory),
        activityStampSink = request.activityStampSink,
        idlePolicy = command.idlePolicy,
      ),
      environmentFields = AgentRunProcessEnvironmentFields(
        environment = command.environment,
        inheritEnvironment = command.inheritEnvironment,
        environmentPassthroughKeys = command.environmentPassthroughKeys,
      ),
      review = AgentRunProcessReviewFields(
        conversationIsolation = command.conversationIsolation,
        reviewEvidenceBroker = request.reviewEvidenceBroker,
        nativeReviewOperations = request.nativeReviewOperations,
        reviewEvidenceEndpoint = request.reviewEvidenceEndpoint,
        spawnAuthorization = request.spawnAuthorization,
      ),
    )

  private fun childSessionId(agent: InstallAgent, request: SkillRunRequest, workingDirectory: Path): String =
    request.auditRepairSessionId ?: request.auditRepairExecutionId ?: buildString {
      append(agent.id)
      append(':')
      append(request.issueKey)
      request.subtaskId?.let { id ->
        append(":subtask-")
        append(id)
      }
      append(':')
      append(workingDirectory.fileName?.toString() ?: workingDirectory.toString())
    }
}

private fun sha256(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

data class DecodedAgentRunOutput(
  val text: String,
  val providerSessionId: String? = null,
  val assistantEventCount: Int? = null,
  val rawOutputPreview: String? = null,
)

interface AgentRunOutputDecoder {
  fun decode(stdout: String): DecodedAgentRunOutput

  fun sessionStarted(line: String): String? = null

  fun undecodable(error: Throwable): Boolean = false

  companion object {
    val PLAIN = decoder { DecodedAgentRunOutput(it) }
    val CLAUDE_JSON = decoder { stdout -> decodeClaudeJson(stdout) }
    val CLAUDE_STREAM_JSON = decoder(::decodeClaudeStreamJson, ::claudeSessionStarted)
    val CODEX_JSONL = decoder(::decodeCodexJsonl, ::codexSessionStarted)
    val CURSOR_STREAM_JSON: AgentRunOutputDecoder = object : AgentRunOutputDecoder {
      override fun decode(stdout: String): DecodedAgentRunOutput = decodeCursorStreamJson(stdout)

      override fun sessionStarted(line: String): String? = structuredSession(line, ::claudeSessionStarted)

      override fun undecodable(error: Throwable): Boolean = error is CursorReviewStreamMalformedError
    }

    private fun decoder(body: (String) -> DecodedAgentRunOutput): AgentRunOutputDecoder = decoder(body) { null }

    private fun decoder(
      body: (String) -> DecodedAgentRunOutput,
      session: (JsonNode) -> String?,
    ): AgentRunOutputDecoder = object : AgentRunOutputDecoder {
      override fun decode(stdout: String): DecodedAgentRunOutput = body(stdout)
      override fun sessionStarted(line: String): String? = structuredSession(line, session)
    }
  }
}

internal val structuredOutputMapper: ObjectMapper by lazy { ObjectMapper() }

private fun decodeClaudeJson(stdout: String): DecodedAgentRunOutput = runCatching {
  val root = structuredOutputMapper.readTree(stdout.trim())
  DecodedAgentRunOutput(
    text = root.path("result").takeIf { it.isTextual }?.asText().orEmpty(),
    providerSessionId = root.path("session_id").takeIf { it.isTextual }?.asText()?.takeIf(String::isNotBlank),
  )
}.getOrElse { DecodedAgentRunOutput(stdout) }

private fun decodeClaudeStreamJson(stdout: String): DecodedAgentRunOutput {
  val providerSessionId = stdout.lineSequence()
    .filter(String::isNotBlank)
    .mapNotNull { line -> runCatching { structuredOutputMapper.readTree(line) }.getOrNull() }
    .mapNotNull(::claudeSessionStarted)
    .firstOrNull { it.isNotBlank() }
  val terminal = stdout.lineSequence()
    .filter(String::isNotBlank)
    .mapNotNull { line ->
      runCatching { structuredOutputMapper.readTree(line) }.getOrNull()
    }
    .lastOrNull { event -> event.path("type").takeIf { it.isTextual }?.asText() == "result" }
    ?: return DecodedAgentRunOutput(
      text = "",
      providerSessionId = providerSessionId,
      rawOutputPreview = stdout.take(RAW_OUTPUT_PREVIEW_MAX_CHARS),
    )
  return DecodedAgentRunOutput(
    text = terminal.path("result").takeIf { it.isTextual }?.asText().orEmpty(),
    providerSessionId = providerSessionId,
  )
}

private fun decodeCodexJsonl(stdout: String): DecodedAgentRunOutput {
  var text: String? = null
  var providerSessionId: String? = null
  var decodedEnvelope = false
  stdout.lineSequence().filter(String::isNotBlank).forEach { line ->
    runCatching { structuredOutputMapper.readTree(line) }.getOrNull()?.let { event ->
      decodedEnvelope = true
      providerSessionId = codexSessionStarted(event) ?: providerSessionId
      event.path("item").path("text").takeIf { it.isTextual }?.asText()?.let { text = it }
    }
  }
  return DecodedAgentRunOutput(
    text = text ?: if (decodedEnvelope) "" else stdout,
    providerSessionId = providerSessionId,
  )
}

private fun structuredSession(line: String, session: (JsonNode) -> String?): String? {
  if (line.isBlank()) return null
  val event = try {
    structuredOutputMapper.readTree(line)
  } catch (_: JsonProcessingException) {
    return null
  }
  return session(event)
}

internal const val RAW_OUTPUT_PREVIEW_MAX_CHARS = 2_000

fun headlessAgentRunAdapters(
  processRunner: AgentRunProcessRunner,
  executableLookup: ExecutableLookup = PathExecutableLookup(),
  databasePath: Path? = null,
): Map<InstallAgent, AgentRunAdapter> = listOf(
  ClaudeAgentRunCommandBuilder(databasePath = databasePath),
  CodexAgentRunCommandBuilder(databasePath = databasePath),
  JunieAgentRunCommandBuilder(databasePath = databasePath),
  CursorAgentRunCommandBuilder(databasePath = databasePath),
).associate { builder ->
  builder.agent to ProcessAgentRunAdapter(
    agent = builder.agent,
    commandBuilder = builder,
    processRunner = processRunner,
    executableLookup = executableLookup,
  )
}

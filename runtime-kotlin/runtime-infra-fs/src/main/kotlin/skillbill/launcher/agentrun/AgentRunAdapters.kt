package skillbill.launcher.agentrun

import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.install.model.AgentLauncherCli
import skillbill.install.model.InstallAgent
import skillbill.install.model.agentLauncherUnavailableMessage
import skillbill.launcher.process.AgentRunProcessRequest
import skillbill.launcher.process.AgentRunProcessRunner
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Path

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
    val result = processRunner.run(processRequest(command, request))
    val decoder = command.outputDecoder ?: commandBuilder.outputDecoder
    val decoded = runCatching { decoder.decode(result.stdout) }.getOrElse { error ->
      if (!decoder.undecodable(error)) throw error
      // A stream we could not decode is not phase output. Handing the raw transport back made the
      // phase schema gate see several conflicting envelopes instead of one undecodable turn, so the
      // operator was told the agent wrote bad output when it had written none we could read.
      DecodedAgentRunOutput(text = "", rawOutputPreview = result.stdout.take(RAW_OUTPUT_PREVIEW_MAX_CHARS))
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
      // SKILL-64 Subtask 3 (AC6, AC11): provider-neutral child-session
      // descriptors derived from launch context the launcher controls — the
      // child working directory (session path) and a deterministic, non-secret
      // session marker (agent + subtask + working dir). No provider-private
      // token-log format is consulted (Non-Goal).
      childSessionPath = command.workingDirectory.toString(),
      childSessionId = childSessionId(agent, request, command.workingDirectory),
      inputTokens = decoded.inputTokens,
      cachedInputTokens = decoded.cachedInputTokens,
      outputTokens = decoded.outputTokens,
      reasoningTokens = decoded.reasoningTokens,
      totalTokens = decoded.totalTokens,
      // This decoder runs after process completion. Completion facts are never an enforceable
      // provider seam, irrespective of what a future command builder can expose in flight.
      providerUsageEnforceable = false,
      assistantEventCount = decoded.assistantEventCount,
      rawOutputPreview = decoded.rawOutputPreview,
    )
  }

  /**
   * Resolves the executable the built command execs. A declared alternate is substituted when the
   * preferred name is absent, which keeps older agent installs that ship only the legacy binary
   * working. Anything else — including the skill-bill goal-continuation driver — has no alternate
   * and is reported by name.
   */
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

  private fun processRequest(command: AgentRunCommand, request: SkillRunRequest) = AgentRunProcessRequest(
    command = command.command,
    workingDirectory = command.workingDirectory,
    timeout = command.timeout,
    stdinText = command.stdinText,
    progressIdleTimeout = request.progressIdleTimeout,
    operationDeadline = request.timeout,
    progressProbe = request.progressProbe,
    declaredProgressProbe = request.declaredProgressProbe,
    mcpStartupProbe = request.mcpStartupProbe,
    progressEmitter = request.progressEmitter,
    activityProbe = WorktreeActivityProbe(command.workingDirectory),
    environment = command.environment,
    inheritEnvironment = command.inheritEnvironment,
    environmentPassthroughKeys = command.environmentPassthroughKeys,
    outputSink = request.outputSink,
    idlePolicy = command.idlePolicy,
    conversationIsolation = command.conversationIsolation,
    reviewEvidenceBroker = request.reviewEvidenceBroker,
    nativeReviewOperations = request.nativeReviewOperations,
    spawnAuthorization = request.spawnAuthorization,
  )

  private fun childSessionId(agent: InstallAgent, request: SkillRunRequest, workingDirectory: Path): String =
    buildString {
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
  java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

data class DecodedAgentRunOutput(
  val text: String,
  val inputTokens: Long? = null,
  val cachedInputTokens: Long? = null,
  val outputTokens: Long? = null,
  val reasoningTokens: Long? = null,
  val totalTokens: Long? = null,
  /** Assistant turns observed on transports that expose them; null when the transport has no such event. */
  val assistantEventCount: Int? = null,
  /** Bounded raw-transport excerpt, set only when decoding produced no usable text. */
  val rawOutputPreview: String? = null,
)

interface AgentRunOutputDecoder {
  fun decode(stdout: String): DecodedAgentRunOutput

  /**
   * Decoder-declared classification of a decode failure. A decoder that owns a transport it cannot
   * always parse says so here; the launcher then degrades that launch to an empty harvest with a
   * bounded preview instead of promoting undecodable bytes to phase output. Decoders that treat
   * every failure as fatal inherit the default and keep propagating.
   */
  fun undecodable(error: Throwable): Boolean = false

  companion object {
    val PLAIN = decoder { DecodedAgentRunOutput(it) }
    val CLAUDE_JSON = decoder { stdout -> decodeClaudeJson(stdout) }
    val CLAUDE_STREAM_JSON = decoder { stdout -> decodeClaudeStreamJson(stdout) }
    val CODEX_JSONL = decoder { stdout -> decodeCodexJsonl(stdout) }
    val CURSOR_STREAM_JSON: AgentRunOutputDecoder = object : AgentRunOutputDecoder {
      override fun decode(stdout: String): DecodedAgentRunOutput = decodeCursorStreamJson(stdout)

      /**
       * A truncated or interleaved Cursor stream is a transport defect, not a provider verdict: the
       * remaining envelopes carry no answer we can read, so the launch is an empty harvest.
       */
      override fun undecodable(error: Throwable): Boolean =
        error is skillbill.infrastructure.fs.CursorReviewStreamMalformedError
    }

    private fun decoder(body: (String) -> DecodedAgentRunOutput): AgentRunOutputDecoder =
      object : AgentRunOutputDecoder {
        override fun decode(stdout: String): DecodedAgentRunOutput = body(stdout)
      }
  }
}

private val structuredOutputMapper: ObjectMapper by lazy { ObjectMapper() }

private fun decodeClaudeJson(stdout: String): DecodedAgentRunOutput = runCatching {
  val root = structuredOutputMapper.readTree(stdout.trim())
  val usage = root.path("usage")
  DecodedAgentRunOutput(
    text = root.path("result").takeIf { it.isTextual }?.asText().orEmpty(),
    inputTokens = usage.longOrNull("input_tokens"),
    cachedInputTokens = usage.longOrNull("cache_read_input_tokens"),
    outputTokens = usage.longOrNull("output_tokens"),
    reasoningTokens = usage.longOrNull("reasoning_tokens"),
    totalTokens = usage.longOrNull("total_tokens"),
  )
}.getOrElse { DecodedAgentRunOutput(stdout) }

/**
 * `--output-format stream-json` emits the same object `--output-format json` would have buffered as
 * its terminal `type: "result"` event, preceded by per-turn events. Decode that event and nothing
 * else so a streamed launch yields byte-identical phase output to a buffered one.
 */
private fun decodeClaudeStreamJson(stdout: String): DecodedAgentRunOutput {
  val terminal = stdout.lineSequence()
    .filter(String::isNotBlank)
    .mapNotNull { line -> runCatching { structuredOutputMapper.readTree(line) }.getOrNull() }
    .lastOrNull { event -> event.path("type").takeIf { it.isTextual }?.asText() == "result" }
    ?: return DecodedAgentRunOutput(stdout)
  val usage = terminal.path("usage")
  return DecodedAgentRunOutput(
    text = terminal.path("result").takeIf { it.isTextual }?.asText().orEmpty(),
    inputTokens = usage.longOrNull("input_tokens"),
    cachedInputTokens = usage.longOrNull("cache_read_input_tokens"),
    outputTokens = usage.longOrNull("output_tokens"),
    reasoningTokens = usage.longOrNull("reasoning_tokens"),
    totalTokens = usage.longOrNull("total_tokens"),
  )
}

private fun decodeCodexJsonl(stdout: String): DecodedAgentRunOutput {
  var text: String? = null
  var usage: com.fasterxml.jackson.databind.JsonNode? = null
  var decodedEnvelope = false
  stdout.lineSequence().filter(String::isNotBlank).forEach { line ->
    runCatching { structuredOutputMapper.readTree(line) }.getOrNull()?.let { event ->
      decodedEnvelope = true
      event.path("item").path("text").takeIf { it.isTextual }?.asText()?.let { text = it }
      event.path("usage").takeUnless { it.isMissingNode || it.isNull }?.let { usage = it }
    }
  }
  return DecodedAgentRunOutput(
    text = text ?: if (decodedEnvelope) "" else stdout,
    inputTokens = usage?.longOrNull("input_tokens"),
    cachedInputTokens = usage?.longOrNull("cached_input_tokens"),
    outputTokens = usage?.longOrNull("output_tokens"),
    reasoningTokens = usage?.longOrNull("reasoning_tokens"),
    totalTokens = usage?.longOrNull("total_tokens"),
  )
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber")
private fun decodeCursorStreamJson(stdout: String): DecodedAgentRunOutput {
  if (stdout.isBlank()) {
    return DecodedAgentRunOutput("")
  }

  var terminalText: String? = null
  var longestAssistantText: String? = null
  var assistantEventCount = 0
  var usage: com.fasterxml.jackson.databind.JsonNode? = null
  var decodedEnvelope = false
  var errorEvent = false
  var errorType: String? = null
  var errorMessage: String? = null
  var totalByteCount = 0
  val maxTotalBytes = 10_000_000 // 10MB limit for Cursor stream processing
  val cursorStreamPreviewLength = 100 // Characters to show in error messages
  val lines = stdout.lineSequence().toList()

  if (lines.isEmpty()) {
    return DecodedAgentRunOutput("")
  }

  lines.asSequence().takeWhile { line ->
    totalByteCount += line.toByteArray().size
    totalByteCount <= maxTotalBytes
  }.filter(String::isNotBlank).forEach { line ->
    val event =
      runCatching { structuredOutputMapper.readTree(line) }.getOrElse {
        throw skillbill.infrastructure.fs.CursorReviewStreamMalformedError(
          "Malformed Cursor stream JSONL line: ${line.take(cursorStreamPreviewLength)}",
          it,
        )
      }
    decodedEnvelope = true
    when (event.path("type").takeIf { it.isTextual }?.asText()) {
      "error" -> {
        errorEvent = true
        errorType = event.path("error_type").takeIf { it.isTextual }?.asText()
        errorMessage = event.path("message").takeIf { it.isTextual }?.asText()
        // Error is stored and thrown later after parsing completes
      }
      "assistant" -> {
        assistantEventCount += 1
        cursorAssistantText(event)?.let { text ->
          if (text.length > (longestAssistantText?.length ?: 0)) longestAssistantText = text
        }
      }
      "result" -> {
        terminalText = event.path("result").takeIf { it.isTextual }?.asText()
        event.path("usage").takeUnless { it.isMissingNode || it.isNull }?.let { usage = it }
      }
    }
  }

  // Throw cursor-specific errors after parsing is complete (reduces throw count)
  if (errorEvent) {
    throw when (errorType) {
      "forbidden_operation" -> skillbill.infrastructure.fs.CursorReviewStreamForbiddenOperationError(
        errorMessage ?: "Cursor reported a forbidden operation",
      )
      "provider_failure" -> skillbill.infrastructure.fs.CursorReviewStreamProviderFailureError(
        errorMessage ?: "Cursor reported a provider failure",
      )
      "termination" -> skillbill.infrastructure.fs.CursorReviewStreamTerminationError(
        errorMessage ?: "Cursor process terminated prematurely",
      )
      else -> skillbill.infrastructure.fs.CursorReviewStreamError(
        errorMessage ?: "Cursor reported an unknown error",
      )
    }
  }

  // A terminal `result` of "" is a real Cursor outcome: the CLI can exit 0 having charged input
  // tokens and produced no answer at all. Fall back to the longest assistant turn so a blank
  // terminal event does not discard text the provider actually emitted, and never promote raw
  // transport bytes to phase output — the phase schema gate reads several JSON envelopes as
  // conflicting candidates rather than as an empty turn.
  val harvested = terminalText?.takeIf(String::isNotBlank) ?: longestAssistantText.orEmpty()
  return DecodedAgentRunOutput(
    text = harvested,
    inputTokens = usage.cursorTokens("inputTokens", "input_tokens"),
    cachedInputTokens = usage.cursorTokens("cachedInputTokens", "cached_input_tokens"),
    outputTokens = usage.cursorTokens("outputTokens", "output_tokens"),
    reasoningTokens = usage.cursorTokens("reasoningTokens", "reasoning_tokens"),
    totalTokens = usage.cursorTokens("totalTokens", "total_tokens"),
    assistantEventCount = assistantEventCount.takeIf { decodedEnvelope },
    rawOutputPreview = stdout.take(RAW_OUTPUT_PREVIEW_MAX_CHARS).takeIf { harvested.isBlank() },
  )
}

/** Cursor emits camelCase usage keys; older captures and fixtures use snake_case. Accept both. */
private fun com.fasterxml.jackson.databind.JsonNode?.cursorTokens(vararg fields: String): Long? =
  this?.let { node -> fields.firstNotNullOfOrNull { field -> node.longOrNull(field) } }

private fun cursorAssistantText(event: com.fasterxml.jackson.databind.JsonNode): String? {
  val content = event.path("message").path("content")
  if (content.isArray) {
    val joined = content.mapNotNull { part -> part.path("text").takeIf { it.isTextual }?.asText() }
      .joinToString("")
    return joined.takeIf(String::isNotBlank)
  }
  return event.path("message").path("text").takeIf { it.isTextual }?.asText()?.takeIf(String::isNotBlank)
    ?: event.path("text").takeIf { it.isTextual }?.asText()?.takeIf(String::isNotBlank)
}

private const val RAW_OUTPUT_PREVIEW_MAX_CHARS = 2_000

private fun com.fasterxml.jackson.databind.JsonNode.longOrNull(field: String): Long? =
  path(field).takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()

fun headlessAgentRunAdapters(
  processRunner: AgentRunProcessRunner,
  executableLookup: ExecutableLookup = PathExecutableLookup(),
): Map<InstallAgent, AgentRunAdapter> = listOf(
  ClaudeAgentRunCommandBuilder(),
  CodexAgentRunCommandBuilder(),
  JunieAgentRunCommandBuilder(),
  CursorAgentRunCommandBuilder(),
).associate { builder ->
  builder.agent to ProcessAgentRunAdapter(
    agent = builder.agent,
    commandBuilder = builder,
    processRunner = processRunner,
    executableLookup = executableLookup,
  )
}

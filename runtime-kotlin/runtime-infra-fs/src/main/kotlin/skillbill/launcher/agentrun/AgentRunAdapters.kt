package skillbill.launcher.agentrun

import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.install.model.InstallAgent
import skillbill.install.model.RUNTIME_REFUSED_AGENTS
import skillbill.launcher.process.AgentRunProcessRequest
import skillbill.launcher.process.AgentRunProcessRunner
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Path

interface AgentRunAdapter {
  val agent: InstallAgent
  val nativeReviewCapabilities: NativeReviewProviderCapabilities
    get() = NativeReviewProviderCapabilities.UNMEDIATED
  fun launch(request: SkillRunRequest): AgentRunLaunchFacts
}

class ProcessAgentRunAdapter(
  override val agent: InstallAgent,
  private val commandBuilder: AgentRunCommandBuilder,
  private val processRunner: AgentRunProcessRunner,
) : AgentRunAdapter {
  override val nativeReviewCapabilities: NativeReviewProviderCapabilities
    get() = commandBuilder.nativeReviewCapabilities

  override fun launch(request: SkillRunRequest): AgentRunLaunchFacts {
    val command = commandBuilder.build(request)
    val result = processRunner.run(
      AgentRunProcessRequest(
        command = command.command,
        workingDirectory = command.workingDirectory,
        timeout = command.timeout,
        stdinText = command.stdinText,
        progressIdleTimeout = request.progressIdleTimeout,
        progressProbe = request.progressProbe,
        declaredProgressProbe = request.declaredProgressProbe,
        progressEmitter = request.progressEmitter,
        activityProbe = WorktreeActivityProbe(command.workingDirectory),
        environment = command.environment,
        inheritEnvironment = command.inheritEnvironment,
        environmentPassthroughKeys = command.environmentPassthroughKeys,
        outputSink = request.outputSink,
        usePtyStdio = command.usePtyStdio,
        idlePolicy = command.idlePolicy,
        conversationIsolation = command.conversationIsolation,
        reviewEvidenceBroker = request.reviewEvidenceBroker,
        nativeReviewOperations = request.nativeReviewOperations,
        nativeReviewLifecycleCallbacks = request.nativeReviewOperations?.let {
          commandBuilder.nativeReviewCapabilities.lifecycleCallbacks?.newSession()
        },
      ),
    )
    val decoded = runCatching {
      (command.outputDecoder ?: commandBuilder.outputDecoder).decode(result.stdout)
    }.getOrElse { error ->
      if (error is skillbill.infrastructure.fs.CursorReviewStreamMalformedError) {
        DecodedAgentRunOutput(result.stdout)
      } else {
        throw error
      }
    }
    val normalizedStdout = normalizeStdout(agent, decoded.text)
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
    )
  }

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
)

fun interface AgentRunOutputDecoder {
  fun decode(stdout: String): DecodedAgentRunOutput

  companion object {
    val PLAIN = AgentRunOutputDecoder { DecodedAgentRunOutput(it) }
    val CLAUDE_JSON = AgentRunOutputDecoder { stdout -> decodeClaudeJson(stdout) }
    val CLAUDE_STREAM_JSON = AgentRunOutputDecoder { stdout -> decodeClaudeStreamJson(stdout) }
    val CODEX_JSONL = AgentRunOutputDecoder { stdout -> decodeCodexJsonl(stdout) }
    val CURSOR_STREAM_JSON = AgentRunOutputDecoder { stdout -> decodeCursorStreamJson(stdout) }
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

  return when {
    decodedEnvelope && terminalText == null -> DecodedAgentRunOutput("")
    !decodedEnvelope -> DecodedAgentRunOutput(stdout)
    else -> DecodedAgentRunOutput(
      text = terminalText ?: "",
      inputTokens = usage?.longOrNull("input_tokens"),
      cachedInputTokens = usage?.longOrNull("cached_input_tokens"),
      outputTokens = usage?.longOrNull("output_tokens"),
      reasoningTokens = usage?.longOrNull("reasoning_tokens"),
      totalTokens = usage?.longOrNull("total_tokens"),
    )
  }
}

private fun com.fasterxml.jackson.databind.JsonNode.longOrNull(field: String): Long? =
  path(field).takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()

private val zcodeStdoutMapper: ObjectMapper by lazy { ObjectMapper() }

private fun normalizeStdout(agent: InstallAgent, stdout: String): String {
  if (agent != InstallAgent.ZCODE) return stdout
  val trimmed = stdout.trim()
  if (!trimmed.startsWith("{")) return stdout
  return runCatching {
    zcodeStdoutMapper.readTree(trimmed).get("response")?.takeIf { node -> node.isTextual }?.asText()
  }.getOrNull() ?: stdout
}

fun headlessAgentRunAdapters(processRunner: AgentRunProcessRunner): Map<InstallAgent, AgentRunAdapter> = listOf(
  ClaudeAgentRunCommandBuilder(),
  CodexAgentRunCommandBuilder(),
  JunieAgentRunCommandBuilder(),
  CursorAgentRunCommandBuilder(),
).filterNot { builder -> RUNTIME_REFUSED_AGENTS.contains(builder.agent) }
  .associate { builder ->
    builder.agent to ProcessAgentRunAdapter(
      agent = builder.agent,
      commandBuilder = builder,
      processRunner = processRunner,
    )
  }

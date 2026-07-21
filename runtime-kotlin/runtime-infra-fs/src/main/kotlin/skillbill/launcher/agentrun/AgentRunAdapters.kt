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
        outputSink = request.outputSink,
        usePtyStdio = command.usePtyStdio,
        idlePolicy = command.idlePolicy,
        conversationIsolation = command.conversationIsolation,
        reviewEvidenceBroker = request.reviewEvidenceBroker,
        nativeReviewOperations = request.nativeReviewOperations,
      ),
    )
    val decoded = commandBuilder.outputDecoder.decode(result.stdout)
    return AgentRunLaunchFacts(
      agent = agent,
      exitStatus = result.exitStatus,
      stdout = normalizeStdout(agent, decoded.text),
      stderr = result.stderr,
      timedOut = result.timedOut,
      interrupted = result.interrupted,
      spawnFailed = result.spawnFailed,
      liveness = result.liveness,
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
      providerUsageEnforceable =
      nativeReviewCapabilities.providerUsageExposure == ProviderUsageExposure.IN_FLIGHT_ENFORCEABLE,
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
    val CODEX_JSONL = AgentRunOutputDecoder { stdout -> decodeCodexJsonl(stdout) }
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
).filterNot { builder -> RUNTIME_REFUSED_AGENTS.contains(builder.agent) }
  .associate { builder ->
    builder.agent to ProcessAgentRunAdapter(
      agent = builder.agent,
      commandBuilder = builder,
      processRunner = processRunner,
    )
  }

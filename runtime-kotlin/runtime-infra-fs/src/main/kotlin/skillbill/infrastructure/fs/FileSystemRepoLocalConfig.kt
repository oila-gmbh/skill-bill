@file:Suppress("MaxLineLength")

package skillbill.infrastructure.fs

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import me.tatarka.inject.annotations.Inject
import skillbill.config.model.RepoLocalConfig
import skillbill.config.model.RepoLocalConfigKey
import skillbill.config.model.parseCodeReviewParallelAgent
import skillbill.config.model.parseSpecType
import skillbill.error.MalformedRepoLocalConfigError
import skillbill.error.UnreadableRepoLocalConfigError
import skillbill.review.context.ProviderTokenThresholds
import skillbill.review.context.ReviewContextBudgetPolicy
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemRepoLocalConfig : RepoLocalConfigPort {
  private val yamlMapper: YAMLMapper by lazy { YAMLMapper() }

  override fun readRepoLocalConfig(request: ReadRepoLocalConfigRequest): ReadRepoLocalConfigResult {
    val configPath = configPath(request.repoRoot)
    if (!Files.exists(configPath)) {
      return ReadRepoLocalConfigResult(RepoLocalConfig.defaults())
    }
    val payload = readConfigPayload(configPath)
    val raw = parseConfigMap(configPath, payload)
    return ReadRepoLocalConfigResult(buildConfig(configPath, raw))
  }

  private fun buildConfig(path: Path, raw: Map<String, Any?>): RepoLocalConfig = RepoLocalConfig(
    specType = parseKnownKey(path, raw, RepoLocalConfigKey.SPEC_TYPE) { value -> parseSpecType(value) }
      ?: RepoLocalConfig.defaults().specType,
    codeReviewParallelAgent = parseKnownKey(path, raw, RepoLocalConfigKey.CODE_REVIEW_PARALLEL_AGENT) { value ->
      parseCodeReviewParallelAgent(value)
    } ?: RepoLocalConfig.defaults().codeReviewParallelAgent,
    reviewContextBudget = parseReviewContextBudget(path, raw["review_context_budget"]),
  )

  private fun parseReviewContextBudget(path: Path, value: Any?): ReviewContextBudgetPolicy {
    if (value == null) return ReviewContextBudgetPolicy.DEFAULT
    val raw = value as? Map<*, *> ?: malformedBudget(path, "review_context_budget", value, "must be a mapping.")
    val allowed = setOf(
      "max_parent_packet_bytes", "max_lane_launch_bytes", "max_lane_evidence_bytes",
      "max_evidence_result_bytes", "max_lane_result_bytes", "max_assignment_expansions", "provider_token_thresholds",
    )
    val unknown = raw.keys.map { it.toString() }.filterNot(allowed::contains)
    if (unknown.isNotEmpty()) malformedBudget(path, "review_context_budget.${unknown.first()}", raw[unknown.first()], "is not a recognized key.")
    val defaults = ReviewContextBudgetPolicy.DEFAULT
    val tokenRaw = raw["provider_token_thresholds"]?.let { nested ->
      nested as? Map<*, *> ?: malformedBudget(path, "review_context_budget.provider_token_thresholds", nested, "must be a mapping.")
    } ?: emptyMap<Any?, Any?>()
    val tokenAllowed = setOf("input_tokens", "cached_input_tokens", "output_tokens", "reasoning_tokens", "total_tokens")
    val tokenUnknown = tokenRaw.keys.map { it.toString() }.filterNot(tokenAllowed::contains)
    if (tokenUnknown.isNotEmpty()) malformedBudget(path, "review_context_budget.provider_token_thresholds.${tokenUnknown.first()}", tokenRaw[tokenUnknown.first()], "is not a recognized key.")
    fun long(source: Map<*, *>, key: String, fallback: Long): Long {
      val rawValue = source[key] ?: return fallback
      return (rawValue as? Number)?.toLong() ?: malformedBudget(path, "review_context_budget.$key", rawValue, "must be an integer.")
    }
    return try {
      ReviewContextBudgetPolicy(
        maxParentPacketBytes = long(raw, "max_parent_packet_bytes", defaults.maxParentPacketBytes),
        maxLaneLaunchBytes = long(raw, "max_lane_launch_bytes", defaults.maxLaneLaunchBytes),
        maxLaneEvidenceBytes = long(raw, "max_lane_evidence_bytes", defaults.maxLaneEvidenceBytes),
        maxEvidenceResultBytes = long(raw, "max_evidence_result_bytes", defaults.maxEvidenceResultBytes),
        maxLaneResultBytes = long(raw, "max_lane_result_bytes", defaults.maxLaneResultBytes),
        maxAssignmentExpansions = long(raw, "max_assignment_expansions", defaults.maxAssignmentExpansions.toLong()).toInt(),
        providerTokenThresholds = ProviderTokenThresholds(
          inputTokens = long(tokenRaw, "input_tokens", defaults.providerTokenThresholds.inputTokens),
          cachedInputTokens = long(tokenRaw, "cached_input_tokens", defaults.providerTokenThresholds.cachedInputTokens),
          outputTokens = long(tokenRaw, "output_tokens", defaults.providerTokenThresholds.outputTokens),
          reasoningTokens = long(tokenRaw, "reasoning_tokens", defaults.providerTokenThresholds.reasoningTokens),
          totalTokens = long(tokenRaw, "total_tokens", defaults.providerTokenThresholds.totalTokens),
        ),
      )
    } catch (error: IllegalArgumentException) {
      throw MalformedRepoLocalConfigError(path.toString(), "review_context_budget", value.toString(), error.message ?: "is inconsistent.", error)
    }
  }

  private fun malformedBudget(path: Path, key: String, value: Any?, reason: String): Nothing =
    throw MalformedRepoLocalConfigError(path.toString(), key, value?.toString() ?: "null", reason)

  private fun <T> parseKnownKey(
    path: Path,
    raw: Map<String, Any?>,
    configKey: RepoLocalConfigKey,
    parser: (String?) -> T?,
  ): T? {
    if (!raw.containsKey(configKey.key)) return null
    val rawValue = raw[configKey.key]
    val asString = rawValue?.toString()
    return parser(asString) ?: throw MalformedRepoLocalConfigError(
      path = path.toString(),
      key = configKey.key,
      value = rawValue?.toString() ?: "null",
      reason = "is not a recognized value for this key.",
    )
  }

  private fun parseConfigMap(path: Path, payload: String): Map<String, Any?> {
    if (payload.isBlank()) return emptyMap()
    return try {
      @Suppress("UNCHECKED_CAST")
      yamlMapper.readValue(payload, Map::class.java) as? Map<String, Any?> ?: emptyMap()
    } catch (error: JacksonException) {
      throw MalformedRepoLocalConfigError(
        path = path.toString(),
        key = "",
        value = "<document>",
        reason = "is not a valid YAML mapping.",
        cause = error,
      )
    }
  }

  private fun readConfigPayload(path: Path): String = try {
    Files.readString(path)
  } catch (error: IOException) {
    throw UnreadableRepoLocalConfigError(path.toString(), error)
  } catch (error: SecurityException) {
    throw UnreadableRepoLocalConfigError(path.toString(), error)
  }
}

internal fun configPath(repoRoot: Path): Path = repoRoot
  .resolve(".skill-bill")
  .resolve(REPO_LOCAL_CONFIG_FILE_NAME)
  .toAbsolutePath()
  .normalize()

internal const val REPO_LOCAL_CONFIG_FILE_NAME: String = "config.yaml"

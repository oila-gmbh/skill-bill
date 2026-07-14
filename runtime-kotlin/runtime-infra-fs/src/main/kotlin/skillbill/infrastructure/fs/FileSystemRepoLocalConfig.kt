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
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.review.context.model.ProviderTokenThresholds
import skillbill.review.context.model.ReviewContextBudgetPolicy
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
    reviewContextBudget = if (raw.containsKey("review_context_budget")) {
      parseReviewContextBudget(path, raw["review_context_budget"])
    } else {
      ReviewContextBudgetPolicy.DEFAULT
    },
  )

  private fun parseReviewContextBudget(path: Path, value: Any?): ReviewContextBudgetPolicy {
    val raw = budgetMapping(path, "review_context_budget", value)
    validateBudgetKeys(
      path,
      raw,
      "review_context_budget",
      setOf(
        "max_parent_packet_bytes",
        "max_lane_launch_bytes",
        "max_lane_evidence_bytes",
        "max_evidence_result_bytes",
        "max_lane_result_bytes",
        "max_assignment_expansions",
        "provider_token_thresholds",
      ),
    )
    val defaults = ReviewContextBudgetPolicy.DEFAULT
    val tokenRaw = providerTokenThresholds(path, raw)
    val tokenDefaults = defaults.providerTokenThresholds
    return try {
      ReviewContextBudgetPolicy(
        maxParentPacketBytes = budgetLong(path, raw, "max_parent_packet_bytes", defaults.maxParentPacketBytes),
        maxLaneLaunchBytes = budgetLong(path, raw, "max_lane_launch_bytes", defaults.maxLaneLaunchBytes),
        maxLaneEvidenceBytes = budgetLong(path, raw, "max_lane_evidence_bytes", defaults.maxLaneEvidenceBytes),
        maxEvidenceResultBytes = budgetLong(path, raw, "max_evidence_result_bytes", defaults.maxEvidenceResultBytes),
        maxLaneResultBytes = budgetLong(path, raw, "max_lane_result_bytes", defaults.maxLaneResultBytes),
        maxAssignmentExpansions = assignmentExpansions(path, raw, defaults.maxAssignmentExpansions),
        providerTokenThresholds = ProviderTokenThresholds(
          inputTokens = tokenLong(path, tokenRaw, "input_tokens", tokenDefaults.inputTokens),
          cachedInputTokens = tokenLong(path, tokenRaw, "cached_input_tokens", tokenDefaults.cachedInputTokens),
          outputTokens = tokenLong(path, tokenRaw, "output_tokens", tokenDefaults.outputTokens),
          reasoningTokens = tokenLong(path, tokenRaw, "reasoning_tokens", tokenDefaults.reasoningTokens),
          totalTokens = tokenLong(path, tokenRaw, "total_tokens", tokenDefaults.totalTokens),
        ),
      )
    } catch (error: IllegalArgumentException) {
      throw MalformedRepoLocalConfigError(
        path.toString(),
        "review_context_budget",
        value.toString(),
        error.message ?: "is inconsistent.",
        error,
      )
    }
  }

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

private fun budgetMapping(path: Path, key: String, value: Any?): Map<*, *> {
  if (value == null) malformedBudget(path, key, value, "must be a mapping, not null.")
  return value as? Map<*, *> ?: malformedBudget(path, key, value, "must be a mapping.")
}

private fun validateBudgetKeys(path: Path, raw: Map<*, *>, prefix: String, allowed: Set<String>) {
  val unknown = raw.entries.firstOrNull { entry -> entry.key.toString() !in allowed } ?: return
  malformedBudget(path, "$prefix.${unknown.key}", unknown.value, "is not a recognized key.")
}

private fun providerTokenThresholds(path: Path, raw: Map<*, *>): Map<*, *> {
  if (!raw.containsKey("provider_token_thresholds")) return emptyMap<Any?, Any?>()
  val prefix = "review_context_budget.provider_token_thresholds"
  val nested = budgetMapping(path, prefix, raw["provider_token_thresholds"])
  validateBudgetKeys(
    path,
    nested,
    prefix,
    setOf("input_tokens", "cached_input_tokens", "output_tokens", "reasoning_tokens", "total_tokens"),
  )
  return nested
}

private fun assignmentExpansions(path: Path, raw: Map<*, *>, fallback: Int): Int {
  val parsed = budgetLong(path, raw, "max_assignment_expansions", fallback.toLong())
  if (parsed !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
    malformedBudget(
      path,
      "review_context_budget.max_assignment_expansions",
      parsed,
      "is outside the signed 32-bit integer range.",
    )
  }
  return parsed.toInt()
}

private fun tokenLong(path: Path, source: Map<*, *>, key: String, fallback: Long): Long = budgetLong(
  path,
  source,
  key,
  fallback,
  "review_context_budget.provider_token_thresholds",
)

private fun budgetLong(
  path: Path,
  source: Map<*, *>,
  key: String,
  fallback: Long,
  prefix: String = "review_context_budget",
): Long {
  if (!source.containsKey(key)) return fallback
  val rawValue = source[key] ?: malformedBudget(path, "$prefix.$key", null, "must be an integer, not null.")
  return when (rawValue) {
    is Byte, is Short, is Int, is Long -> (rawValue as Number).toLong()
    is java.math.BigInteger -> rawValue.longValueExactOrNull()
      ?: malformedBudget(path, "$prefix.$key", rawValue, "is outside the signed 64-bit integer range.")
    else -> malformedBudget(path, "$prefix.$key", rawValue, "must be an exact integer.")
  }
}

private fun malformedBudget(path: Path, key: String, value: Any?, reason: String): Nothing =
  throw MalformedRepoLocalConfigError(path.toString(), key, value?.toString() ?: "null", reason)

internal fun configPath(repoRoot: Path): Path = repoRoot
  .resolve(".skill-bill")
  .resolve(REPO_LOCAL_CONFIG_FILE_NAME)
  .toAbsolutePath()
  .normalize()

internal const val REPO_LOCAL_CONFIG_FILE_NAME: String = "config.yaml"

private fun java.math.BigInteger.longValueExactOrNull(): Long? = try {
  longValueExact()
} catch (@Suppress("SwallowedException") ignored: ArithmeticException) {
  null
}

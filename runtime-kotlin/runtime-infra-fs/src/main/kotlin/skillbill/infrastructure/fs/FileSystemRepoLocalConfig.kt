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
  )

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

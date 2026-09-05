package skillbill.contracts.workflow

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import skillbill.contracts.LOCALE_STABLE_SCHEMA_CONFIG
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException

internal val featureTaskRuntimePhaseOutputLog: Logger =
  Logger.getLogger("skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaValidator")

internal const val FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE: String =
  FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE

internal const val FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH: String =
  FeatureTaskRuntimePhaseOutputSchemaPaths.REPO_RELATIVE_PATH

internal fun loadFeatureTaskRuntimePhaseOutputSchema(): JsonSchema {
  var failure: Throwable? = null
  try {
    val yamlText = readFeatureTaskRuntimePhaseOutputSchemaText()
    val yamlNode = YAMLMapper().readTree(yamlText)
    FeatureTaskRuntimePhaseOutputSchemaValidator.assertIdentity(yamlNode)
    val jsonText = ObjectMapper().writeValueAsString(yamlNode)
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    return factory.getSchema(jsonText, LOCALE_STABLE_SCHEMA_CONFIG)
  } catch (cancellation: CancellationException) {
    failure = cancellation
  } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
    logFeatureTaskRuntimePhaseOutputSchemaLoadFailure(error)
    failure = error
  } catch (error: JsonProcessingException) {
    logFeatureTaskRuntimePhaseOutputSchemaLoadFailure(error)
    failure = error
  }
  throw failure
}

private fun logFeatureTaskRuntimePhaseOutputSchemaLoadFailure(error: Throwable) {
  featureTaskRuntimePhaseOutputLog.log(
    Level.SEVERE,
    "Failed to load canonical feature-task-runtime phase output schema: " +
      "classpath='$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE' " +
      "repoRelativePath='$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH' " +
      "errorType='${error::class.qualifiedName}' message='${error.message.orEmpty()}'",
    error,
  )
}

internal fun readFeatureTaskRuntimePhaseOutputSchemaText(): String {
  FeatureTaskRuntimePhaseOutputSchemaValidator::class.java.classLoader
    .getResourceAsStream(FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }
  val walkAnchor: Path = Path.of("").toAbsolutePath()
  val resolved = walkForFeatureTaskRuntimePhaseOutputSchemaFile(walkAnchor)
  if (resolved != null) {
    return Files.readString(resolved)
  }
  throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
    sourceLabel = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE,
    reason = "Canonical feature-task-runtime phase output schema is missing. Expected to find it on the JVM " +
      "classpath at '$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE' or on disk under " +
      "'$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH' walked up from: $walkAnchor.",
  )
}

private val FENCED_BLOCK = Regex("```[ \\t]*[A-Za-z0-9_-]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

internal fun phaseOutputObjectCandidates(raw: String): List<String> {
  val trimmed = raw.trim()
  return buildList {
    FENCED_BLOCK.findAll(trimmed).map { it.groupValues[1].trim() }.toList().asReversed().forEach(::add)
    balancedTopLevelObjectSpans(trimmed).asReversed().forEach(::add)
    val open = trimmed.indexOf('{')
    val close = trimmed.lastIndexOf('}')
    if (open in 0 until close) {
      add(trimmed.substring(open, close + 1))
    }
    add(trimmed)
  }.filter(String::isNotBlank).distinct()
}

internal fun balancedTopLevelObjectSpans(text: String): List<String> {
  val scanner = TopLevelObjectScanner(text)
  return text.indices.mapNotNull(scanner::consume)
}

private class TopLevelObjectScanner(private val text: String) {
  private var depth = 0
  private var start = -1
  private var inString = false
  private var escaped = false

  fun consume(index: Int): String? {
    val ch = text[index]
    if (inString) {
      advanceStringState(ch)
      return null
    }
    return advanceStructuralState(ch, index)
  }

  private fun advanceStringState(ch: Char) {
    if (escaped) {
      escaped = false
      return
    }
    when (ch) {
      '\\' -> escaped = true
      '"' -> inString = false
    }
  }

  private fun advanceStructuralState(ch: Char, index: Int): String? {
    when (ch) {
      '"' -> inString = true
      '{' -> openObject(index)
      '}' -> return closeObject(index)
    }
    return null
  }

  private fun openObject(index: Int) {
    if (depth == 0) start = index
    depth += 1
  }

  private fun closeObject(index: Int): String? {
    if (depth == 0) return null
    depth -= 1
    if (depth != 0 || start < 0) return null
    val span = text.substring(start, index + 1)
    start = -1
    return span
  }
}

private fun walkForFeatureTaskRuntimePhaseOutputSchemaFile(hint: Path): Path? {
  var current: Path? = hint.toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) {
      return candidate
    }
    current = current.parent
  }
  return null
}

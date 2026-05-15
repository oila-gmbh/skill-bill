package skillbill.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import kotlin.io.path.exists

internal const val SKILL_BILL_DESKTOP_UI_SCALE_PROPERTY = "skillbill.desktop.uiScale"
internal const val SKILL_BILL_DESKTOP_UI_SCALE_ENV = "SKILLBILL_DESKTOP_UI_SCALE"

private const val DEFAULT_DENSITY = 1f
private const val SCALE_EPSILON = 0.05f
private const val MIN_REASONABLE_SCALE = 0.5f
private const val MAX_REASONABLE_SCALE = 4f
private const val KWIN_RC = "kwinrc"
private const val KWIN_OUTPUT_CONFIG = "kwinoutputconfig.json"

internal data class DesktopUiScaleConfiguration(
  val windowScale: Float = DEFAULT_DENSITY,
  val contentDensity: Float? = null,
)

internal fun resolveDesktopUiScale(
  environment: Map<String, String> = System.getenv(),
  systemProperties: Properties = System.getProperties(),
  configHome: Path = defaultConfigHome(environment, systemProperties),
  osName: String = System.getProperty("os.name").orEmpty(),
  awtDensity: Float = currentAwtDensity(),
): DesktopUiScaleConfiguration {
  val sanitizedAwtDensity = awtDensity.sanitizedScale() ?: DEFAULT_DENSITY
  val explicitScale =
    parseUiScale(systemProperties.getProperty(SKILL_BILL_DESKTOP_UI_SCALE_PROPERTY))
      ?: parseUiScale(environment[SKILL_BILL_DESKTOP_UI_SCALE_ENV])

  if (explicitScale != null) {
    return scaleConfiguration(targetDensity = explicitScale, awtDensity = sanitizedAwtDensity)
  }

  if (!osName.contains("linux", ignoreCase = true) || sanitizedAwtDensity > DEFAULT_DENSITY + SCALE_EPSILON) {
    return DesktopUiScaleConfiguration()
  }

  val detectedScale = sequenceOf(
    { parseUiScale(environment["QT_SCALE_FACTOR"]) },
    { parseUiScale(environment["GDK_SCALE"]) },
    { detectKdeScale(environment = environment, configHome = configHome) },
  )
    .mapNotNull { detectScale -> detectScale() }
    .firstOrNull { scale -> scale > DEFAULT_DENSITY + SCALE_EPSILON }

  return if (detectedScale != null) {
    scaleConfiguration(targetDensity = detectedScale, awtDensity = sanitizedAwtDensity)
  } else {
    DesktopUiScaleConfiguration()
  }
}

private fun scaleConfiguration(targetDensity: Float, awtDensity: Float): DesktopUiScaleConfiguration {
  val windowScale = (targetDensity / awtDensity).coerceIn(MIN_REASONABLE_SCALE, MAX_REASONABLE_SCALE)
  return DesktopUiScaleConfiguration(windowScale = windowScale, contentDensity = targetDensity)
}

private fun detectKdeScale(environment: Map<String, String>, configHome: Path): Float? {
  val desktopName = listOfNotNull(
    environment["XDG_CURRENT_DESKTOP"],
    environment["DESKTOP_SESSION"],
  ).joinToString(separator = " ").lowercase()

  if ("kde" !in desktopName && "plasma" !in desktopName) {
    return null
  }

  return readKwinXwaylandScale(configHome.resolve(KWIN_RC))
    ?: readKdeOutputScale(configHome.resolve(KWIN_OUTPUT_CONFIG))
}

internal fun readKwinXwaylandScale(path: Path): Float? {
  if (!path.exists()) {
    return null
  }

  var inXwaylandSection = false
  return runCatching {
    Files.readAllLines(path).firstNotNullOfOrNull { line ->
      val trimmedLine = line.trim()
      when {
        trimmedLine.startsWith("[") && trimmedLine.endsWith("]") -> {
          inXwaylandSection = trimmedLine == "[Xwayland]"
          null
        }
        inXwaylandSection -> {
          val separatorIndex = trimmedLine.indexOf("=")
          if (separatorIndex <= 0) {
            null
          } else {
            val key = trimmedLine.take(separatorIndex).trim()
            val value = trimmedLine.drop(separatorIndex + 1).trim()
            value.takeIf { key == "Scale" }?.let(::parseUiScale)
          }
        }
        else -> null
      }
    }
  }.getOrNull()
}

internal fun readKdeOutputScale(path: Path): Float? {
  if (!path.exists()) {
    return null
  }

  val root = runCatching {
    Json.parseToJsonElement(Files.readString(path))
  }.getOrNull() as? JsonArray ?: return null

  val outputScales = root
    .sectionData("outputs")
    .mapNotNull { output -> output.jsonObjectOrNull()?.number("scale")?.sanitizedScale() }

  if (outputScales.isEmpty()) {
    return null
  }

  val primaryOutputIndex = root
    .sectionData("setups")
    .firstNotNullOfOrNull { setup ->
      setup
        .jsonObjectOrNull()
        ?.array("outputs")
        ?.mapNotNull { output ->
          val outputObject = output.jsonObjectOrNull() ?: return@mapNotNull null
          if (outputObject.boolean("enabled") == false) {
            return@mapNotNull null
          }
          val outputIndex = outputObject.int("outputIndex") ?: return@mapNotNull null
          val priority = outputObject.int("priority") ?: Int.MAX_VALUE
          priority to outputIndex
        }
        ?.minByOrNull { (priority, _) -> priority }
        ?.second
    }

  return primaryOutputIndex
    ?.let(outputScales::getOrNull)
    ?: outputScales.maxOrNull()
}

private fun JsonArray.sectionData(name: String): JsonArray = firstNotNullOfOrNull { section ->
  val sectionObject = section.jsonObjectOrNull() ?: return@firstNotNullOfOrNull null
  val sectionName = sectionObject.string("name") ?: return@firstNotNullOfOrNull null
  sectionObject.array("data").takeIf { sectionName == name }
} ?: JsonArray(emptyList())

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull()

private fun JsonObject.number(key: String): Float? = (this[key] as? JsonPrimitive)?.contentOrNull()?.toFloatOrNull()

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull()?.toIntOrNull()

private fun JsonObject.boolean(key: String): Boolean? =
  (this[key] as? JsonPrimitive)?.contentOrNull()?.toBooleanStrictOrNull()

private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonPrimitive.contentOrNull(): String? = runCatching { jsonPrimitive.content }.getOrNull()

private fun parseUiScale(value: String?): Float? {
  val rawValue = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
  val percentScale = rawValue.endsWith("%")
  val normalizedValue = rawValue
    .removeSuffix("%")
    .removeSuffix("x")
    .removeSuffix("X")
    .trim()

  val numericValue = normalizedValue.toFloatOrNull() ?: return null
  val scale = when {
    percentScale -> numericValue / 100f
    numericValue > 10f -> numericValue / 100f
    else -> numericValue
  }

  return scale.sanitizedScale()
}

private fun Float.sanitizedScale(): Float? =
  takeIf { it.isFinite() && it in MIN_REASONABLE_SCALE..MAX_REASONABLE_SCALE }

private fun defaultConfigHome(environment: Map<String, String>, systemProperties: Properties): Path {
  environment["XDG_CONFIG_HOME"]?.takeIf(String::isNotBlank)?.let {
    return Paths.get(it)
  }

  return Paths.get(systemProperties.getProperty("user.home").orEmpty(), ".config")
}

private fun currentAwtDensity(): Float = runCatching {
  if (GraphicsEnvironment.isHeadless()) {
    DEFAULT_DENSITY
  } else {
    GraphicsEnvironment
      .getLocalGraphicsEnvironment()
      .defaultScreenDevice
      .defaultConfiguration
      .defaultTransform
      .scaleX
      .toFloat()
  }
}.getOrDefault(DEFAULT_DENSITY)

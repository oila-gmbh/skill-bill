package skillbill.telemetry

import java.nio.file.Path

fun expandAndNormalizeTelemetryPath(rawPath: String, userHome: Path): Path {
  val normalized =
    when {
      rawPath == "~" -> userHome.toString()
      rawPath.startsWith("~/") -> userHome.resolve(rawPath.removePrefix("~/")).toString()
      else -> rawPath
    }
  return Path.of(normalized).toAbsolutePath().normalize()
}

fun telemetryProxyUrl(customProxyUrl: String): Pair<String, String?> {
  val hostedRelayUrl = DEFAULT_TELEMETRY_PROXY_URL.trimEnd('/')
  var normalizedCustomProxyUrl = customProxyUrl.trimEnd('/')
  if (normalizedCustomProxyUrl == hostedRelayUrl) {
    normalizedCustomProxyUrl = ""
  }
  val proxyUrl = if (normalizedCustomProxyUrl.isNotEmpty()) normalizedCustomProxyUrl else hostedRelayUrl
  return proxyUrl to normalizedCustomProxyUrl.ifBlank { null }
}

package skillbill.telemetry

fun telemetryProxyUrl(customProxyUrl: String): Pair<String, String?> {
  val hostedRelayUrl = DEFAULT_TELEMETRY_PROXY_URL.trimEnd('/')
  var normalizedCustomProxyUrl = customProxyUrl.trimEnd('/')
  if (normalizedCustomProxyUrl == hostedRelayUrl) {
    normalizedCustomProxyUrl = ""
  }
  val proxyUrl = if (normalizedCustomProxyUrl.isNotEmpty()) normalizedCustomProxyUrl else hostedRelayUrl
  return proxyUrl to normalizedCustomProxyUrl.ifBlank { null }
}

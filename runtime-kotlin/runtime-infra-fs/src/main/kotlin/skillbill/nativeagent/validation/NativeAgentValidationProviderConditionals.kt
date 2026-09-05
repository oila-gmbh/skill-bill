package skillbill.nativeagent.validation

import skillbill.nativeagent.rendering.NativeAgentProvider

private val PROVIDER_CONDITIONAL_HANDLEBARS_REGEX: Regex = Regex(
  "\\{\\{\\s*#\\s*(${NativeAgentProvider.entries.joinToString("|") { it.name.lowercase() }})\\s*\\}\\}",
  RegexOption.IGNORE_CASE,
)

private val PROVIDER_CONDITIONAL_CASE_INSENSITIVE: List<String> = listOf(
  "if provider ==",
  "if (provider",
)

internal fun containsNativeAgentProviderConditional(body: String): Boolean {
  if (PROVIDER_CONDITIONAL_HANDLEBARS_REGEX.containsMatchIn(body)) {
    return true
  }
  val lowered = body.lowercase()
  return PROVIDER_CONDITIONAL_CASE_INSENSITIVE.any { token -> token in lowered }
}

package skillbill.nativeagent.rendering

internal fun normalizeMarkdownLineEndings(text: String): String = text
  .replace("\r\n", "\n")
  .replace('\r', '\n')

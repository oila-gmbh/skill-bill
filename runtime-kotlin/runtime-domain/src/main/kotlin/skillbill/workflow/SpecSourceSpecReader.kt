package skillbill.workflow

import skillbill.workflow.model.SpecSource

object SpecSourceSpecReader {
  private val SPEC_SOURCE_LINE = Regex("""(?im)^\s*spec[_ -]source\s*:\s*([^\r\n#]+)(?:\s+#.*)?$""")

  fun parseSpecSource(specText: String): SpecSource {
    val rawValue = SPEC_SOURCE_LINE.find(specText)?.groupValues?.get(1)?.trim()
      ?: return SpecSource.LOCAL
    return SpecSource.fromWireValue(rawValue)
      ?: throw IllegalArgumentException(
        "Unknown spec_source '$rawValue'. Allowed: ${SpecSource.entries.joinToString { it.wireValue }}.",
      )
  }
}

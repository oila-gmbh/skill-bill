package skillbill.workflow

import skillbill.workflow.model.SpecSource

/**
 * Pure parser for the optional `spec_source:` line in a single_spec `spec.md`.
 * Operates on the spec text alone (no IO) so the infra reader can reuse it.
 * Absence resolves to [SpecSource.LOCAL]; an unrecognized value fails loudly,
 * mirroring the feature-size line reader.
 */
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

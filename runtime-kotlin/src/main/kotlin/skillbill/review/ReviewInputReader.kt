package skillbill.review

import kotlin.io.path.readText

object ReviewInputReader {
  fun readInput(inputPath: String, stdinText: String? = null): Pair<String, String?> {
    if (inputPath == "-") {
      require(stdinText != null) { "stdinText is required when inputPath is '-'." }
      return stdinText to null
    }
    val path = expandAndNormalizePath(inputPath)
    return path.readText() to path.toString()
  }
}

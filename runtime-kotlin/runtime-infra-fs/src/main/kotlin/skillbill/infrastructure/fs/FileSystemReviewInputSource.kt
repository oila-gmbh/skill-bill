package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.review.ReviewInputSource
import skillbill.review.expandAndNormalizePath
import java.nio.file.Files

@Inject
class FileSystemReviewInputSource : ReviewInputSource {
  override fun readInput(inputPath: String, stdinText: String?): Pair<String, String?> {
    if (inputPath == "-") {
      require(stdinText != null) { "stdinText is required when inputPath is '-'." }
      return stdinText to null
    }
    val path = expandAndNormalizePath(inputPath)
    return Files.readString(path) to path.toString()
  }
}

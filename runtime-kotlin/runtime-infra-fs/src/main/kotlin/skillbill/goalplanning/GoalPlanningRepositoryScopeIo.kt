package skillbill.goalplanning

import skillbill.text.Utf8Text
import java.nio.file.Files
import java.nio.file.Path

internal fun goalPlanningReadFileOrNull(path: Path, maxBytes: Long): BoundaryFileRead? {
  val cap = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
  val bytes = runCatching {
    Files.newInputStream(path).use { input -> input.readNBytes(cap) }
  }.getOrNull() ?: return null
  return BoundaryFileRead(bytes.decodeToString(), cut = bytes.size >= cap)
}

internal fun goalPlanningTruncateToUtf8Bytes(text: String, maxBytes: Int): String =
  Utf8Text.truncateToUtf8Bytes(text, maxBytes)

internal fun goalPlanningUtf8Size(text: String): Int = Utf8Text.utf8Size(text)

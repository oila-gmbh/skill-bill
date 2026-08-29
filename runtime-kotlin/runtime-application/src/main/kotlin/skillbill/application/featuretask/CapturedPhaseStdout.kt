package skillbill.application.featuretask

internal data class CapturedPhaseStdout(
  val text: String,
  val bytes: ByteArray,
  val truncated: Boolean,
  val byteSize: Long,
  val sha256: String,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CapturedPhaseStdout) return false
    return text == other.text &&
      bytes.contentEquals(other.bytes) &&
      truncated == other.truncated &&
      byteSize == other.byteSize &&
      sha256 == other.sha256
  }

  override fun hashCode(): Int {
    var result = text.hashCode()
    result = 31 * result + bytes.contentHashCode()
    result = 31 * result + truncated.hashCode()
    result = 31 * result + byteSize.hashCode()
    result = 31 * result + sha256.hashCode()
    return result
  }
}

package skillbill.text

object Utf8Text {
  fun truncateToUtf8Bytes(text: String, maxBytes: Int): String {
    if (text.length <= maxBytes / MAX_UTF8_BYTES_PER_CHAR) return text
    val encoded = text.encodeToByteArray()
    if (encoded.size <= maxBytes) return text
    var end = maxBytes
    while (end > 0 && (encoded[end].toInt() and CONTINUATION_MASK) == CONTINUATION_MARKER) end -= 1
    return encoded.decodeToString(0, end)
  }

  fun utf8Size(text: String): Int = text.encodeToByteArray().size

  private const val MAX_UTF8_BYTES_PER_CHAR = 3
  private const val CONTINUATION_MASK = 0xC0
  private const val CONTINUATION_MARKER = 0x80
}

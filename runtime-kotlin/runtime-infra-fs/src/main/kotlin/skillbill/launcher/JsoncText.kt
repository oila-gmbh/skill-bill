package skillbill.launcher

internal object JsoncText {
  fun stripComments(text: String): String {
    val state = JsoncScanState()
    val result = StringBuilder()
    while (state.index < text.length) {
      val ch = text[state.index]
      val next = text.getOrNull(state.index + 1)
      when {
        state.inLineComment -> consumeLineComment(state, ch, result)
        state.inBlockComment -> consumeBlockComment(state, ch, next)
        state.inString -> consumeString(state, ch, result)
        ch == '/' && next == '/' -> startLineComment(state)
        ch == '/' && next == '*' -> startBlockComment(state)
        ch == '"' -> startString(state, ch, result)
        else -> result.append(ch)
      }
      state.index += 1
    }
    return result.toString()
  }

  fun stripTrailingCommas(text: String): String {
    val state = JsoncScanState()
    val result = StringBuilder()
    while (state.index < text.length) {
      val ch = text[state.index]
      if (state.inString) {
        consumeString(state, ch, result)
      } else if (ch == '"') {
        startString(state, ch, result)
      } else if (!isTrailingComma(text, state.index, ch)) {
        result.append(ch)
      }
      state.index += 1
    }
    return result.toString()
  }

  private fun consumeLineComment(state: JsoncScanState, ch: Char, result: StringBuilder) {
    if (ch == '\n' || ch == '\r') {
      state.inLineComment = false
      result.append(ch)
    }
  }

  private fun consumeBlockComment(state: JsoncScanState, ch: Char, next: Char?) {
    if (ch == '*' && next == '/') {
      state.inBlockComment = false
      state.index += 1
    }
  }

  private fun consumeString(state: JsoncScanState, ch: Char, result: StringBuilder) {
    result.append(ch)
    if (state.escaped) {
      state.escaped = false
    } else if (ch == '\\') {
      state.escaped = true
    } else if (ch == '"') {
      state.inString = false
    }
  }

  private fun startLineComment(state: JsoncScanState) {
    state.inLineComment = true
    state.index += 1
  }

  private fun startBlockComment(state: JsoncScanState) {
    state.inBlockComment = true
    state.index += 1
  }

  private fun startString(state: JsoncScanState, ch: Char, result: StringBuilder) {
    state.inString = true
    result.append(ch)
  }

  private fun isTrailingComma(text: String, index: Int, ch: Char): Boolean =
    ch == ',' && text.drop(index + 1).firstOrNull { !it.isWhitespace() } in listOf('}', ']')
}

private data class JsoncScanState(
  var index: Int = 0,
  var inString: Boolean = false,
  var escaped: Boolean = false,
  var inLineComment: Boolean = false,
  var inBlockComment: Boolean = false,
)

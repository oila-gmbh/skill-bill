@file:Suppress("TooGenericExceptionCaught", "LongMethod")

package skillbill.contracts.workflow

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import java.security.MessageDigest

internal class YamlFlowScalarValidator(private val text: String) {
  private var inDouble = false
  private var inSingle = false
  private var escaped = false
  private val plain = StringBuilder()

  fun validate(): Boolean {
    text.forEachIndexed { index, ch ->
      if (!consume(index, ch)) return false
    }
    return !inDouble && !inSingle && flushPlain()
  }

  private fun consume(index: Int, ch: Char): Boolean = when {
    inDouble -> {
      consumeDoubleQuoted(ch)
      true
    }
    inSingle -> {
      if (ch == '\'' && text.getOrNull(index + 1) != '\'') inSingle = false
      true
    }
    else -> consumeUnquoted(ch)
  }

  private fun consumeDoubleQuoted(ch: Char) {
    if (escaped) {
      escaped = false
    } else {
      when (ch) {
        '\\' -> escaped = true
        '"' -> inDouble = false
      }
    }
  }

  private fun consumeUnquoted(ch: Char): Boolean = when (ch) {
    '"' -> flushPlain().also { valid -> if (valid) inDouble = true }
    '\'' -> flushPlain().also { valid -> if (valid) inSingle = true }
    ':' -> flushPlain(beforeColon = true)
    ',', '{', '}', '[', ']' -> flushPlain()
    else -> true.also { if (!ch.isWhitespace()) plain.append(ch) }
  }

  private fun flushPlain(beforeColon: Boolean = false): Boolean {
    val token = plain.toString().trim()
    plain.clear()
    return when {
      token.isEmpty() -> true
      beforeColon -> token.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))
      else -> token.matches(Regex("[-+]?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) ||
        token in setOf("true", "false", "null", "~")
    }
  }
}

internal class DelimiterScanner(private val text: String) {
  private val stack = ArrayDeque<Char>()
  private val unmatched = mutableListOf<Int>()
  private var firstMismatch: MismatchedClosing? = null
  private var inDouble = false
  private var inSingle = false
  private var escaped = false

  fun scan(): DelimiterScan {
    text.forEachIndexed(::consume)
    return DelimiterScan(stack.toList(), unmatched, firstMismatch)
  }

  private fun consume(index: Int, ch: Char) {
    when {
      inDouble -> consumeDoubleQuoted(ch)
      inSingle -> if (ch == '\'' && text.getOrNull(index + 1) != '\'') inSingle = false
      else -> consumeStructural(index, ch)
    }
  }

  private fun consumeDoubleQuoted(ch: Char) {
    if (escaped) {
      escaped = false
    } else {
      when (ch) {
        '\\' -> escaped = true
        '"' -> inDouble = false
      }
    }
  }

  private fun consumeStructural(index: Int, ch: Char) {
    when (ch) {
      '"' -> inDouble = true
      '\'' -> inSingle = true
      '{' -> stack.addLast('}')
      '[' -> stack.addLast(']')
      '}', ']' -> consumeClosing(index, ch)
    }
  }

  private fun consumeClosing(index: Int, ch: Char) {
    if (stack.lastOrNull() == ch) {
      stack.removeLast()
    } else {
      unmatched += index
      if (firstMismatch == null) {
        val missingCloser = stack.lastOrNull()
        val enclosingCloser = stack.elementAtOrNull(stack.size - 2)
        firstMismatch = MismatchedClosing(
          offset = index,
          missingCloser = missingCloser.takeIf { it != null && enclosingCloser == ch },
        )
      }
    }
  }
}


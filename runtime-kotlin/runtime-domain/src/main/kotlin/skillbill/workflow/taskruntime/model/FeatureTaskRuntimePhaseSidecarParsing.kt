package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport

const val FEATURE_TASK_RUNTIME_COMMIT_SUBJECT_BEGIN: String = "<<<COMMIT_SUBJECT>>>"
const val FEATURE_TASK_RUNTIME_COMMIT_SUBJECT_END: String = "<<<END_COMMIT_SUBJECT>>>"
const val FEATURE_TASK_RUNTIME_DECOMPOSITION_PACKAGE_BEGIN: String = "<<<DECOMPOSITION_PACKAGE>>>"
const val FEATURE_TASK_RUNTIME_DECOMPOSITION_PACKAGE_END: String = "<<<END_DECOMPOSITION_PACKAGE>>>"
const val FEATURE_TASK_RUNTIME_HANDOFF_TRUNCATION_MARKER: String =
  "\n\n<<<HANDOFF_TRUNCATED>>> remainder omitted under budget\n"

private val DERIVATION_CRITICAL_LINE = Regex(
  """(?i)(approved|changes_requested|satisfied|gaps_found|findings_verified|no_findings_verified|""" +
    """completed|blocked|failed|task[-_ ]?\d+|F-\d+|AC-\d+)""",
)

fun readDelimitedBlock(prose: String, begin: String, end: String): String? {
  val start = prose.indexOf(begin)
  if (start < 0) return null
  val contentStart = start + begin.length
  val endIndex = prose.indexOf(end, contentStart)
  if (endIndex < 0) return null
  return prose.substring(contentStart, endIndex).trim().takeIf(String::isNotBlank)
}

fun readCommitSubjectFromProse(prose: String): String? =
  readDelimitedBlock(prose, FEATURE_TASK_RUNTIME_COMMIT_SUBJECT_BEGIN, FEATURE_TASK_RUNTIME_COMMIT_SUBJECT_END)

fun readDecompositionPackageJsonFromProse(prose: String): String? = readDelimitedBlock(
  prose,
  FEATURE_TASK_RUNTIME_DECOMPOSITION_PACKAGE_BEGIN,
  FEATURE_TASK_RUNTIME_DECOMPOSITION_PACKAGE_END,
)

@OpenBoundaryMap("Feature-task-runtime decomposition package parsed from delimited plan prose")
fun decompositionPackageMapFromProse(prose: String): Map<String, Any?>? {
  val raw = readDecompositionPackageJsonFromProse(prose) ?: return null
  val parsed = JsonSupport.parseObjectOrNull(raw) ?: return null
  return JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
}

fun retainDerivationCriticalProsePrefix(text: String, maxUtf8Bytes: Int): String {
  if (text.encodeToByteArray().size <= maxUtf8Bytes) return text
  val lines = text.lineSequence().toList()
  val critical = lines.filter { DERIVATION_CRITICAL_LINE.containsMatchIn(it) }
  val criticalBlock = critical.joinToString("\n")
  val criticalBytes = criticalBlock.encodeToByteArray().size
  if (criticalBytes >= maxUtf8Bytes) {
    return truncateUtf8(criticalBlock, maxUtf8Bytes.coerceAtLeast(0))
  }
  val remainingBudget = maxUtf8Bytes - criticalBytes - FEATURE_TASK_RUNTIME_HANDOFF_TRUNCATION_MARKER
    .encodeToByteArray().size
  if (remainingBudget <= 0) {
    return criticalBlock + FEATURE_TASK_RUNTIME_HANDOFF_TRUNCATION_MARKER
  }
  val narrative = lines.filterNot { DERIVATION_CRITICAL_LINE.containsMatchIn(it) }.joinToString("\n")
  val narrativePrefix = truncateUtf8(narrative, remainingBudget)
  return buildString {
    if (criticalBlock.isNotBlank()) {
      append(criticalBlock)
      append('\n')
    }
    append(narrativePrefix)
    append(FEATURE_TASK_RUNTIME_HANDOFF_TRUNCATION_MARKER)
  }
}

private const val UTF8_CONTINUATION_BYTE_MASK = 0xC0
private const val UTF8_CONTINUATION_BYTE_PATTERN = 0x80

private fun truncateUtf8(text: String, maxBytes: Int): String {
  if (maxBytes <= 0) return ""
  val bytes = text.encodeToByteArray()
  if (bytes.size <= maxBytes) return text
  var end = maxBytes
  while (end > 0 && (bytes[end - 1].toInt() and UTF8_CONTINUATION_BYTE_MASK) == UTF8_CONTINUATION_BYTE_PATTERN) {
    end--
  }
  return bytes.copyOfRange(0, end).decodeToString()
}

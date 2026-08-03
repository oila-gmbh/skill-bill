package skillbill.workflow.taskruntime.model

internal inline fun requireRule(condition: Boolean, payloadFreeMessage: String, message: () -> String) {
  if (!condition) throw FeatureTaskRuntimeAuditRepairRuleViolation(message(), payloadFreeMessage)
}

// A rejection message quotes the offending value so the author can find it, but the value may be the
// oversized payload that was just rejected, so the excerpt stays bounded and single-line.
private const val REJECTION_PREVIEW_CHARS: Int = 80

private fun String.preview(): String {
  val flattened = map { if (it.isISOControl()) ' ' else it }.joinToString("")
  return if (flattened.length <= REJECTION_PREVIEW_CHARS) {
    flattened
  } else {
    flattened.take(REJECTION_PREVIEW_CHARS) + "…"
  }
}

internal fun requireNonBlank(value: String, field: String) {
  requireRule(value.isNotBlank(), "$field must be nonblank.") { "$field must be nonblank." }
  requireDurableText(value, field)
}

internal fun requireNonBlankList(values: List<String>, field: String) {
  requireRule(
    values.isNotEmpty(),
    "$field must contain at least one entry.",
  ) { "$field must contain at least one entry, was empty." }
  requireRule(values.all(String::isNotBlank), "$field entries must be nonblank.") {
    "$field entries must be nonblank; blank at ${values.indexOfFirst(String::isBlank)}."
  }
}

internal fun requireCompactList(values: List<String>, field: String, maximumItems: Int) {
  requireRule(values.size <= maximumItems, "$field allows at most $maximumItems entries.") {
    "$field allows at most $maximumItems entries, had ${values.size}."
  }
  values.forEach { requireDurableText(it, "$field entry") }
}

// Every durable text field either describes a code defect or names the work that repairs it, so all of
// them must be able to carry symbols and commands: `=`, `[]`, and `<>` are ordinary content
// (`--tests=`, `results[0]`, `List<String>`). Rejecting that punctuation made the fields unsatisfiable
// for their own purpose. A lone backtick is the same case: a gap in governed markdown has to quote the
// prose it is about, and that prose carries inline code. Only the code fence it was guarding against is
// rejected. Pasted payloads are excluded structurally instead. The identical rule lives in
// `compactSummary` in feature-task-runtime-audit-repair-plan-schema.yaml, pinned by
// FeatureTaskRuntimeAuditRepairSchemaParityTest.
internal fun requireDurableText(value: String, field: String) {
  requireRule(
    value.length <= MAX_AUDIT_REPAIR_TEXT_LENGTH,
    "$field allows at most $MAX_AUDIT_REPAIR_TEXT_LENGTH characters.",
  ) {
    "$field allows at most $MAX_AUDIT_REPAIR_TEXT_LENGTH characters, had ${value.length}."
  }
  requireRule(
    value.none(Char::isISOControl),
    "$field must be a single-line durable value with no line break or control character.",
  ) {
    "$field must be a single-line durable value; \"${value.preview()}\" contains a line break or control character."
  }
  requireRule(
    !value.contains(CODE_FENCE),
    "$field must not contain a code fence; remove the fenced block.",
  ) {
    "$field must not contain a code fence; remove the fenced block from \"${value.preview()}\"."
  }
  requireRule(
    !SERIALIZED_PAYLOAD.containsMatchIn(value),
    "$field must be a short single-line description, not serialized, patch, or tool-output syntax.",
  ) {
    "$field must be a short single-line description, not serialized, patch, or tool-output syntax; " +
      "\"${value.preview()}\" looks like a pasted payload."
  }
  requireRule(
    !SUMMARY_ROLE_PREFIX.containsMatchIn(value),
    "$field must not contain a prompt transcript; a role prefix is forbidden.",
  ) {
    "$field must not contain a prompt transcript; \"${value.preview()}\" starts with a role prefix."
  }
}

private const val CODE_FENCE: String = "```"

private val SERIALIZED_PAYLOAD = Regex(
  "\\{\\s*\"|\"\\s*:\\s*[\\[{\"]|@@[^@]*@@|^(?:diff --git|\\+\\+\\+ |--- )",
)
private val SUMMARY_ROLE_PREFIX = Regex(
  "(?i)^\\s*(system|user|assistant|developer|tool)(?:\\s+(?:prompt|message|output))?\\s*:",
)

internal fun requireUnique(values: List<String>, field: String) {
  val duplicates = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
  requireRule(duplicates.isEmpty(), "$field must be unique.") { "$field must be unique, duplicated $duplicates." }
}

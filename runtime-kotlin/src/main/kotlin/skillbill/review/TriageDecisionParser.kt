package skillbill.review

object TriageDecisionParser {
  fun expandBulkDecisions(rawDecisions: List<String>, numberedFindings: List<NumberedFinding>): List<String> =
    buildList {
      rawDecisions.forEach { rawDecision ->
        val stripped = rawDecision.trim()
        val structuredExpansion = expandStructuredDecision(stripped)
        if (structuredExpansion != null) {
          addAll(structuredExpansion)
        } else {
          addAll(expandBulkDecision(stripped, numberedFindings))
        }
      }
    }

  fun expandStructuredDecision(rawDecision: String): List<String>? {
    if ("=" !in rawDecision || "[" !in rawDecision || "]" !in rawDecision) {
      return null
    }
    val matches = triageSelectionEntryPattern.findAll(rawDecision).toList()
    require(matches.isNotEmpty()) { INVALID_STRUCTURED_DECISION_MESSAGE }
    val expanded = mutableListOf<String>()
    var cursor = 0
    matches.forEach { match ->
      val separator = rawDecision.substring(cursor, match.range.first)
      require(triageSelectionSeparatorPattern.matches(separator)) {
        INVALID_STRUCTURED_DECISION_MESSAGE
      }
      val action = match.groups["action"]?.value.orEmpty()
      val numbersBlock = match.groups["numbers"]?.value.orEmpty().trim()
      expanded += expandStructuredNumbers(numbersBlock, action)
      cursor = match.range.last + 1
    }
    require(triageSelectionSeparatorPattern.matches(rawDecision.substring(cursor))) {
      INVALID_STRUCTURED_DECISION_MESSAGE
    }
    return expanded
  }

  fun parseTriageDecisions(rawDecisions: List<String>, numberedFindings: List<NumberedFinding>): List<TriageDecision> {
    val expandedDecisions = expandBulkDecisions(rawDecisions, numberedFindings)
    val numberToFinding = numberedFindings.associateBy({ it.number }, { it.findingId })
    val seenNumbers = mutableSetOf<Int>()
    return expandedDecisions.map { rawDecision ->
      val match =
        triageDecisionPattern.matchEntire(rawDecision.trim())
          ?: throw IllegalArgumentException(INVALID_TRIAGE_DECISION_MESSAGE)
      val number = match.groups["number"]?.value.orEmpty().toInt()
      require(number in numberToFinding) {
        "Unknown finding number '$number' for the current review run."
      }
      require(seenNumbers.add(number)) {
        "Duplicate triage decision for finding number '$number'."
      }
      TriageDecision(
        number = number,
        findingId = numberToFinding.getValue(number),
        outcomeType = normalizeTriageAction(match.groups["action"]?.value.orEmpty()),
        note = normalizeTriageNote(match.groups["note"]?.value),
      )
    }
  }

  fun normalizeTriageAction(rawAction: String): String = when (rawAction.trim().lowercase()) {
    "false positive", "false-positive", "false_positive" -> "false_positive"
    "fix" -> "fix_applied"
    "accept", "accepted" -> "finding_accepted"
    "edit", "edited" -> "finding_edited"
    "dismiss", "skip", "reject" -> "fix_rejected"
    else -> throw IllegalArgumentException("Unsupported triage action '$rawAction'.")
  }

  fun normalizeTriageNote(rawNote: String?): String {
    val note = rawNote.orEmpty().trim()
    return if (note.isNotEmpty() && !meaningfulNotePattern.containsMatchIn(note)) "" else note
  }

  private fun expandBulkDecision(rawDecision: String, numberedFindings: List<NumberedFinding>): List<String> {
    val bulkMatch = bulkTriagePattern.matchEntire(rawDecision) ?: return listOf(rawDecision)
    val action = bulkMatch.groups["action"]?.value.orEmpty()
    val note = bulkMatch.groups["note"]?.value.orEmpty()
    return numberedFindings.map { entry ->
      val suffix = if (note.isNotBlank()) " - $note" else ""
      "${entry.number} $action$suffix"
    }
  }

  private fun expandStructuredNumbers(numbersBlock: String, action: String): List<String> {
    if (numbersBlock.isEmpty()) {
      return emptyList()
    }
    return numbersBlock.split(",").map { rawNumber ->
      val number = rawNumber.trim()
      require(number.toIntOrNull() != null) {
        "Invalid structured triage decision format. Lists must contain only finding numbers, " +
          "got '$number'."
      }
      "$number $action"
    }
  }
}

private val meaningfulNotePattern = Regex("[A-Za-z0-9]")
private val triageDecisionPattern =
  Regex(
    "^\\s*(?<number>\\d+)\\s+" +
      "(?<action>false[-_ ]positive|fix|accept|accepted|edit|edited|dismiss|skip|reject)" +
      "(?:\\s*(?:[:-]\\s*|\\s+)(?<note>.+))?\\s*$",
    setOf(RegexOption.IGNORE_CASE),
  )
private val bulkTriagePattern =
  Regex(
    "^\\s*all\\s+" +
      "(?<action>false[-_ ]positive|fix|accept|accepted|edit|edited|dismiss|skip|reject)" +
      "(?:\\s*(?:[:-]\\s*|\\s+)(?<note>.+))?\\s*$",
    setOf(RegexOption.IGNORE_CASE),
  )
private val triageSelectionEntryPattern =
  Regex(
    "(?<action>false[-_ ]positive|fix|accept|accepted|edit|edited|dismiss|skip|reject)\\s*=\\s*\\[(?<numbers>[^]]*)]",
    setOf(RegexOption.IGNORE_CASE),
  )
private val triageSelectionSeparatorPattern = Regex("[\\s,]*")
private const val INVALID_STRUCTURED_DECISION_MESSAGE: String =
  "Invalid structured triage decision format. Use entries like 'fix=[1] reject=[2,3]'."
private const val INVALID_TRIAGE_DECISION_MESSAGE: String =
  "Invalid triage decision format. Use entries like '1 fix', " +
    "'2 skip - intentional', 'all fix', or 'fix=[1] reject=[2]'."

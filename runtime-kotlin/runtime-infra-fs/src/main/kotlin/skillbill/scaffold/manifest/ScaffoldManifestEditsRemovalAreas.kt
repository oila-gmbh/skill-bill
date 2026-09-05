
package skillbill.scaffold.manifest

private val AREAS_LIST_PATTERN =
  Regex("^declared_code_review_areas:\\s*\\n((?:[ \\t]+-[^\\n]*\\n)*)", RegexOption.MULTILINE)
private val AREAS_FILES_PATTERN =
  Regex("^(declared_files:\\n(?:(?:[ \\t]+[^\\n]*\\n)*?))(  areas:\\n)((?:    [^\\n]+\\n)*)", RegexOption.MULTILINE)
private val AREA_METADATA_BLOCK_PATTERN =
  Regex("^(area_metadata:\\n)((?:  [^\\n]+\\n|    [^\\n]+\\n)*)", RegexOption.MULTILINE)

internal fun removeAreaFromList(text: String, area: String): String {
  val match = AREAS_LIST_PATTERN.find(text) ?: return text
  val body = match.groupValues[1]
  val areaLinePattern = Regex(
    "^[ \\t]+-\\s*(?:\"|')?${Regex.escape(area)}(?:\"|')?\\s*\\n",
    RegexOption.MULTILINE,
  )
  val newBody = areaLinePattern.replace(body, "")
  if (newBody == body) {
    return text
  }
  if (newBody.isBlank()) {
    return text.replaceRange(match.range, "declared_code_review_areas: []\n")
  }
  return text.replaceRange(match.range, "declared_code_review_areas:\n$newBody")
}

internal fun removeAreaFromDeclaredFiles(text: String, area: String): String {
  val match = AREAS_FILES_PATTERN.find(text) ?: return text
  val prefix = match.groupValues[1]
  val header = match.groupValues[2]
  val body = match.groupValues[MANIFEST_AREAS_BODY_GROUP_INDEX]
  val areaEntryPattern = Regex(
    "^$MANIFEST_AREAS_ENTRY_INDENT${Regex.escape(area)}:[^\\n]*\\n",
    RegexOption.MULTILINE,
  )
  val newBody = areaEntryPattern.replace(body, "")
  if (newBody == body) {
    return text
  }
  if (newBody.isBlank()) {
    return text.replaceRange(match.range, prefix + "  areas: {}\n")
  }
  return text.replaceRange(match.range, prefix + header + newBody)
}

internal fun removeAreaMetadata(text: String, area: String): String {
  val match = AREA_METADATA_BLOCK_PATTERN.find(text) ?: return text
  val header = match.groupValues[1]
  val body = match.groupValues[2]
  val entryPattern = Regex(
    "^  ${Regex.escape(area)}:\\s*\\n(?:    [^\\n]*\\n)*",
    RegexOption.MULTILINE,
  )
  val newBody = entryPattern.replace(body, "")
  if (newBody == body) {
    return text
  }
  if (newBody.isBlank()) {
    return text.replaceRange(match.range, "area_metadata: {}\n")
  }
  return text.replaceRange(match.range, header + newBody)
}

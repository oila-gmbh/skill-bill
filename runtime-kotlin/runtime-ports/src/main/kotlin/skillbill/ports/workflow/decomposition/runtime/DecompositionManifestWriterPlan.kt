package skillbill.ports.workflow.decomposition.runtime

fun parentSpecPath(plan: Map<String, Any?>): String {
  when (val rawParentPath = plan["parent_spec_path"]) {
    is String -> if (rawParentPath.isNotBlank()) return rawParentPath
    null -> Unit
    else -> invalidManifest("<planning-result>", "parent_spec_path must be a string when present.")
  }
  val firstSubtask = (plan["subtasks"] as? List<*>).orEmpty().firstOrNull().asStringAnyMapOrNull()
    ?: invalidManifest("<planning-result>", "decomposition planning result must contain subtasks.")
  return firstSubtask.stringValue("spec_path", "<planning-result>", "subtasks[0].spec_path")
}

private fun Map<String, Any?>.stringValue(key: String, sourceLabel: String, fieldPath: String): String =
  (this[key] as? String)?.takeIf(String::isNotBlank)
    ?: invalidManifest(sourceLabel, "$fieldPath must be a nonblank string.")

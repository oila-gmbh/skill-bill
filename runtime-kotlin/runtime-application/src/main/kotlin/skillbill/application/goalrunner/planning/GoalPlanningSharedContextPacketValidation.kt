package skillbill.application.goalrunner.planning

import skillbill.contracts.JsonSupport
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext

internal object GoalPlanningSharedContextPacketValidation {
  private val BOUNDARY_MEMORY_FIELDS = setOf("catalog", "truncated")
  private val CATALOG_ENTRY_FIELDS = setOf("heading_id", "source_path", "kind", "heading")
  private val CATALOG_KINDS = setOf(GoalPlanningContext.KIND_HISTORY, GoalPlanningContext.KIND_DECISIONS)
  private val SUBTASK_FIELDS = setOf("id", "name", "spec_path", "planning_disposition", "dependencies")
  private val DEPENDENCY_FIELDS = setOf("subtask_id", "optional", "skipped")
  private val DISPOSITIONS = setOf("included", "skipped")

  fun requireValidCatalog(value: Any?) {
    val boundaryMemory = value as? Map<*, *> ?: error("shared context boundary memory is invalid")
    require(boundaryMemory.keys == BOUNDARY_MEMORY_FIELDS) { "shared context boundary memory is invalid" }
    require(boundaryMemory["truncated"] is Boolean) { "shared context boundary memory truncation flag is invalid" }
    val catalog = boundaryMemory["catalog"] as? List<*> ?: error("shared context boundary memory catalog is invalid")
    require(catalog.size <= GoalPlanningContext.MAX_CATALOG_HEADINGS) {
      "shared context boundary memory catalog exceeds the heading cap"
    }
    val headingIds = mutableSetOf<String>()
    for (raw in catalog) {
      validateCatalogEntry(raw, headingIds)
    }
  }

  private fun validateCatalogEntry(raw: Any?, headingIds: MutableSet<String>) {
    val entry = raw as? Map<*, *> ?: error("shared context boundary memory catalog entry is invalid")
    require(entry.keys == CATALOG_ENTRY_FIELDS) { "shared context boundary memory catalog entry fields are invalid" }
    require(entry.values.all { it is String }) { "shared context boundary memory catalog entry is invalid" }
    val sourcePath = entry["source_path"] as String
    require(sourcePath.isNotBlank() && !sourcePath.startsWith("/") && ".." !in sourcePath) {
      "shared context boundary memory source path is invalid"
    }
    require(entry["kind"] as String in CATALOG_KINDS) { "shared context boundary memory kind is invalid" }
    require((entry["heading"] as String).length <= GoalPlanningContext.MAX_HEADING_TEXT_CHARS) {
      "shared context boundary memory heading exceeds the length cap"
    }
    require(headingIds.add(entry["heading_id"] as String)) {
      "shared context boundary memory heading ids must be unique"
    }
  }

  fun normalizedSubtasks(value: Any?): List<Map<String, Any?>> {
    val entries = value as? List<*> ?: error("shared context ordered subtasks must be a list")
    return entries.map { entry ->
      val subtask = entry as? Map<*, *> ?: error("shared context ordered subtask must be an object")
      require(subtask.keys == SUBTASK_FIELDS) { "shared context ordered subtask fields are invalid" }
      val id = (subtask["id"] as? Number)?.toInt()
        ?: error("shared context ordered subtask id is invalid")
      val name = subtask["name"] as? String
        ?: error("shared context ordered subtask name is invalid")
      val specPath = subtask["spec_path"] as? String
        ?: error("shared context ordered subtask spec path is invalid")
      val disposition = subtask["planning_disposition"] as? String
        ?: error("shared context ordered subtask planning disposition is invalid")
      require(disposition in DISPOSITIONS) { "shared context ordered subtask planning disposition is invalid" }
      linkedMapOf(
        "id" to id,
        "name" to name,
        "spec_path" to specPath,
        "planning_disposition" to disposition,
        "dependencies" to normalizedDependencies(subtask["dependencies"]),
      )
    }
  }

  private fun normalizedDependencies(value: Any?): List<Map<String, Any?>> {
    val dependencies = value as? List<*> ?: error("shared context subtask dependencies must be a list")
    return dependencies.map { entry ->
      val dependency = entry as? Map<*, *> ?: error("shared context subtask dependency must be an object")
      require(dependency.keys == DEPENDENCY_FIELDS) { "shared context subtask dependency fields are invalid" }
      linkedMapOf(
        "subtask_id" to (
          (dependency["subtask_id"] as? Number)?.toInt()
            ?: error("shared context dependency subtask id is invalid")
          ),
        "optional" to (
          dependency["optional"] as? Boolean
            ?: error("shared context dependency optional flag is invalid")
          ),
        "skipped" to (
          dependency["skipped"] as? Boolean
            ?: error("shared context dependency skipped flag is invalid")
          ),
      )
    }
  }

  fun isStringMap(value: Any?): Boolean =
    value is Map<*, *> && value.keys.all { it is String } && value.values.all { it is String }

  fun digest(packet: Map<String, Any?>): String = sha256HexUtf8(JsonSupport.mapToJsonString(packet))
}

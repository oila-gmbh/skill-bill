package skillbill.application.goalrunner

import skillbill.application.featuretask.sha256HexUtf8
import skillbill.contracts.JsonSupport
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask

/**
 * The durable shared-context packet the goal sweep writes into the preplan payload and reads back on
 * resume. Every function here is pure over maps, so the sweep can validate a recovered packet without
 * re-reading the repository.
 */
internal object GoalPlanningSharedContextPacket {
  const val VERSION = "0.1"
  const val MAX_GOVERNED_CONTEXT_CHARS = 65_536
  private const val MAX_PACKET_CHARS = 524_288

  private val PACKET_FIELDS = setOf(
    "packet_version",
    "repository_identity",
    "normalized_issue_key",
    "parent_spec_path",
    "parent_spec",
    "decomposition_manifest",
    "platform_packs",
    "boundary_memory",
    "validation_guidance",
    "ordered_subtasks",
    "integrity_sha256",
  )
  private val SUBTASK_FIELDS = setOf("id", "name", "spec_path", "planning_disposition", "dependencies")
  private val DEPENDENCY_FIELDS = setOf("subtask_id", "optional", "skipped")
  private val DISPOSITIONS = setOf("included", "skipped")

  fun validate(
    packet: Map<String, Any?>,
    repositoryIdentity: String,
    normalizedIssueKey: String,
    parentSpecPath: String,
    subtasks: List<DecompositionSubtask>,
  ) {
    require(packet.keys == PACKET_FIELDS) { "shared context packet fields are invalid" }
    require(packet["packet_version"] == VERSION) { "shared context packet version is invalid" }
    require(packet["repository_identity"] == repositoryIdentity) { "shared context repository identity is invalid" }
    require(packet["normalized_issue_key"] == normalizedIssueKey) { "shared context issue key is invalid" }
    require(packet["parent_spec_path"] == parentSpecPath) { "shared context parent spec path is invalid" }
    require(packet["parent_spec"] is String) { "shared context parent spec is invalid" }
    require((packet["decomposition_manifest"] as? String)?.length?.let { it <= MAX_GOVERNED_CONTEXT_CHARS } == true) {
      "shared context decomposition manifest is malformed"
    }
    require(isStringMap(packet["platform_packs"])) { "shared context platform packs are invalid" }
    require(isStringMap(packet["boundary_memory"])) { "shared context boundary memory is invalid" }
    require(packet["validation_guidance"] is String) { "shared context validation guidance is invalid" }
    val recoveredTopology = normalizedSubtasks(packet["ordered_subtasks"]).map { it - "planning_disposition" }
    val expectedTopology = normalizedSubtasks(orderedSubtasks(subtasks)).map { it - "planning_disposition" }
    require(recoveredTopology == expectedTopology) { "shared context ordered subtasks are invalid" }
    require(JsonSupport.mapToJsonString(packet).length <= MAX_PACKET_CHARS) {
      "shared context packet exceeds the size limit"
    }
    require(packet["integrity_sha256"] == digest(packet - "integrity_sha256")) {
      "shared context packet integrity is invalid"
    }
  }

  fun orderedSubtasks(subtasks: List<DecompositionSubtask>): List<Map<String, Any?>> = subtasks.map { subtask ->
    linkedMapOf(
      "id" to subtask.id,
      "name" to subtask.name,
      "spec_path" to subtask.specPath,
      "planning_disposition" to if (subtask.status == "skipped") "skipped" else "included",
      "dependencies" to subtask.dependencies.map { dependency ->
        linkedMapOf(
          "subtask_id" to dependency.subtaskId,
          "optional" to dependency.optional,
          "skipped" to dependency.skipped,
        )
      },
    )
  }

  fun includedSubtaskIds(packet: Map<String, Any?>): Set<Int> = normalizedSubtasks(packet["ordered_subtasks"])
    .mapNotNull { subtask -> (subtask["id"] as Int).takeIf { subtask["planning_disposition"] == "included" } }
    .toSet()

  fun digest(packet: Map<String, Any?>): String = sha256HexUtf8(JsonSupport.mapToJsonString(packet))

  /** The provenance hash covers only manifest fields a resume may not change. */
  fun immutableDecompositionHash(manifest: DecompositionManifest): String {
    val immutable = linkedMapOf<String, Any?>(
      "contract_version" to manifest.contractVersion,
      "issue_key" to manifest.issueKey,
      "feature_name" to manifest.featureName,
      "parent_spec_path" to manifest.parentSpecPath,
      "spec_source" to manifest.specSource.wireValue,
      "execution_model" to manifest.executionModel.wireValue,
      "base_branch" to manifest.baseBranch,
      "feature_branch" to manifest.featureBranch,
      "stack_branches" to manifest.stackBranches.map {
        linkedMapOf("subtask_id" to it.subtaskId, "branch" to it.branch, "base_branch" to it.baseBranch)
      },
      "subtasks" to manifest.subtasks.map { subtask ->
        linkedMapOf(
          "id" to subtask.id,
          "name" to subtask.name,
          "spec_path" to subtask.specPath,
          "linear_issue_id" to subtask.linearIssueId,
          "dependencies" to subtask.dependencies.map { dependency ->
            linkedMapOf(
              "subtask_id" to dependency.subtaskId,
              "optional" to dependency.optional,
              "skipped" to dependency.skipped,
            )
          },
        )
      },
    )
    return sha256HexUtf8(JsonSupport.mapToJsonString(immutable))
  }

  private fun normalizedSubtasks(value: Any?): List<Map<String, Any?>> {
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

  private fun isStringMap(value: Any?): Boolean =
    value is Map<*, *> && value.keys.all { it is String } && value.values.all { it is String }
}

/**
 * Compares two revisions of a governed planning spec while ignoring the `status:` frontmatter line the
 * goal runner itself rewrites, so a status flip does not read as parent-spec drift on resume.
 */
internal object GoalPlanningSpecCanonicalization {
  private const val FRONTMATTER_FENCE = "---"
  private val STATUS_FRONTMATTER_LINE = Regex("^status\\s*:.*$")

  fun canonical(spec: String): String {
    val lines = spec.lines()
    if (lines.firstOrNull() != FRONTMATTER_FENCE) return spec
    val closingFenceIndex = lines.indexOfFirstFrom(1) { it == FRONTMATTER_FENCE }
    if (closingFenceIndex < 0) return spec
    val frontmatter = lines.subList(1, closingFenceIndex)
    val withoutStatus = frontmatter.filterNot { STATUS_FRONTMATTER_LINE.matches(it) }
    if (withoutStatus.size == frontmatter.size) return spec
    val body = lines.drop(closingFenceIndex + 1)
    return if (withoutStatus.all(String::isBlank)) {
      body.dropWhileAtMostOne(String::isBlank).joinToString("\n")
    } else {
      (listOf(FRONTMATTER_FENCE) + withoutStatus + FRONTMATTER_FENCE + body).joinToString("\n")
    }
  }

  private fun <T> List<T>.indexOfFirstFrom(startIndex: Int, predicate: (T) -> Boolean): Int {
    for (index in startIndex until size) {
      if (predicate(this[index])) return index
    }
    return -1
  }

  private fun <T> List<T>.dropWhileAtMostOne(predicate: (T) -> Boolean): List<T> =
    if (firstOrNull()?.let(predicate) == true) drop(1) else this
}

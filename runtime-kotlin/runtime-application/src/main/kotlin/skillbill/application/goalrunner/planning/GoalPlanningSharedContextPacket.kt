package skillbill.application.goalrunner.planning

import skillbill.contracts.JsonSupport
import skillbill.contracts.goalplanning.GoalPlanningDiscoveryExclusions
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.workflow.decomposition.model.DecompositionSubtask

internal object GoalPlanningSharedContextPacket {
  const val VERSION = "0.4"
  const val LEGACY_VERSION_0_3 = "0.3"
  const val LEGACY_VERSION_0_2 = "0.2"
  const val LEGACY_VERSION_0_1 = "0.1"
  const val MAX_GOVERNED_CONTEXT_CHARS = 65_536
  private const val MAX_PACKET_CHARS = 524_288

  internal val PACKET_FIELDS = setOf(
    "packet_version",
    "repository_identity",
    "normalized_issue_key",
    "parent_spec_path",
    "parent_spec",
    "decomposition_manifest",
    "boundary_memory",
    "validation_guidance",
    "ordered_subtasks",
    "integrity_sha256",
  )
  internal val LEGACY_V01_FIELDS = PACKET_FIELDS + "platform_packs"

  fun migrate(packet: Map<String, Any?>): Map<String, Any?> = when (val version = packet["packet_version"]) {
    VERSION -> withoutExcludedCatalogEntries(packet)
    LEGACY_VERSION_0_3 -> GoalPlanningSharedContextPacketLegacy.migrateFromV03(packet)
    LEGACY_VERSION_0_2 ->
      GoalPlanningSharedContextPacketLegacy.migrateFromV03(GoalPlanningSharedContextPacketLegacy.migrateFromV02(packet))
    LEGACY_VERSION_0_1 -> GoalPlanningSharedContextPacketLegacy.migrateFromV03(
      GoalPlanningSharedContextPacketLegacy.migrateFromV02(
        GoalPlanningSharedContextPacketLegacy.migrateFromV01(packet),
      ),
    )
    else -> error(
      "shared context packet version '$version' is unsupported; expected '$VERSION', " +
        "'$LEGACY_VERSION_0_3', '$LEGACY_VERSION_0_2', or '$LEGACY_VERSION_0_1'",
    )
  }

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
    GoalPlanningSharedContextPacketValidation.requireValidCatalog(packet["boundary_memory"])
    require(packet["validation_guidance"] is String) { "shared context validation guidance is invalid" }
    val recoveredTopology = GoalPlanningSharedContextPacketValidation.normalizedSubtasks(packet["ordered_subtasks"])
      .map { it - "planning_disposition" }
    val expectedTopology = GoalPlanningSharedContextPacketValidation.normalizedSubtasks(orderedSubtasks(subtasks))
      .map { it - "planning_disposition" }
    require(recoveredTopology == expectedTopology) { "shared context ordered subtasks are invalid" }
    require(JsonSupport.mapToJsonString(packet).length <= MAX_PACKET_CHARS) {
      "shared context packet exceeds the size limit"
    }
    require(packet["integrity_sha256"] == digest(packet - "integrity_sha256")) {
      "shared context packet integrity is invalid"
    }
  }

  private fun withoutExcludedCatalogEntries(packet: Map<String, Any?>): Map<String, Any?> {
    val catalog = (packet["boundary_memory"] as? Map<*, *>)?.get("catalog") as? List<*> ?: return packet
    val retained = catalog.filter { entry ->
      val sourcePath = (entry as? Map<*, *>)?.get("source_path") as? String
      sourcePath != null && !GoalPlanningDiscoveryExclusions.isExcluded(sourcePath)
    }
    if (retained.size == catalog.size) return packet
    require(packet["integrity_sha256"] == digest(packet - "integrity_sha256")) {
      "shared context packet integrity is invalid"
    }
    val migrated = packet.toMutableMap()
    migrated["boundary_memory"] = linkedMapOf<String, Any?>("catalog" to retained, "truncated" to true)
    migrated.remove("integrity_sha256")
    return migrated + ("integrity_sha256" to digest(migrated))
  }

  fun emptyCatalog(): Map<String, Any?> = linkedMapOf("catalog" to emptyList<Map<String, Any?>>(), "truncated" to false)

  fun discardedCatalog(): Map<String, Any?> =
    linkedMapOf("catalog" to emptyList<Map<String, Any?>>(), "truncated" to true)

  fun catalogHeadingIds(packet: Map<String, Any?>): Set<String> =
    ((packet["boundary_memory"] as? Map<*, *>)?.get("catalog") as? List<*>)
      .orEmpty()
      .mapNotNull { entry -> (entry as? Map<*, *>)?.get("heading_id") as? String }
      .toSet()

  fun catalog(context: GoalPlanningContext): Map<String, Any?> = linkedMapOf(
    "catalog" to context.boundaryCatalog.map { heading ->
      linkedMapOf<String, Any?>(
        "heading_id" to heading.headingId,
        "source_path" to heading.sourcePath,
        "kind" to heading.kind,
        "heading" to heading.heading,
      )
    },
    "truncated" to context.boundaryCatalogTruncated,
  )

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

  fun includedSubtaskIds(packet: Map<String, Any?>): Set<Int> =
    GoalPlanningSharedContextPacketValidation.normalizedSubtasks(packet["ordered_subtasks"])
      .mapNotNull { subtask -> (subtask["id"] as Int).takeIf { subtask["planning_disposition"] == "included" } }
      .toSet()

  fun digest(packet: Map<String, Any?>): String = GoalPlanningSharedContextPacketValidation.digest(packet)
}

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

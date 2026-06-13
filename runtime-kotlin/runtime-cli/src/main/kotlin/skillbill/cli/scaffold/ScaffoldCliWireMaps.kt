package skillbill.cli.scaffold

import skillbill.ports.scaffold.catalog.model.ScaffoldExplainSkill
import skillbill.ports.scaffold.model.ScaffoldReviewComposition
import skillbill.ports.scaffold.model.ScaffoldSectionStatus
import skillbill.ports.scaffold.model.ScaffoldSkillStatus

/**
 * SKILL-52.3 subtask 3 — Shared wire-map rebuilders for the typed scaffold result
 * models. These helpers reconstruct the legacy ordered `LinkedHashMap` wire shapes in
 * EXACT producer key order so the byte-equivalence contract locked by
 * `runtime-cli/src/test/kotlin/skillbill/cli/CliScaffoldRuntimeTest.kt` and
 * `AuthoringOperationsTest.kt` holds. They are consumed by the `toCliMap()` adapter
 * mappers in [ScaffoldCliResultMappers].
 */

/**
 * Rebuilds the legacy `statusPayload(...)` ordered wire map from the typed
 * [ScaffoldSkillStatus] in EXACT producer key order. The optional `review_composition`,
 * `content_preview` / `content`, and `issues` tail keys are appended only when present,
 * mirroring the conditional `payload[...] = ...` writes in the prior producer.
 */
internal fun ScaffoldSkillStatus.toWireMap(): Map<String, Any?> {
  val map = linkedMapOf<String, Any?>(
    "skill_name" to skillName,
    "package" to packageName,
    "platform" to platform,
    "family" to family,
    "area" to area,
    "content_file" to contentFile,
    "render_command" to renderCommand,
    "completion_status" to completionStatus,
    "section_count" to sectionCount,
    "sections" to sections.map(ScaffoldSectionStatus::toWireMap),
    "recommended_commands" to recommendedCommands,
  )
  reviewComposition?.let { map["review_composition"] = it.toWireMap() }
  contentPreview?.let { map["content_preview"] = it }
  content?.let { map["content"] = it }
  issues?.let { map["issues"] = it }
  return map
}

internal fun ScaffoldSectionStatus.toWireMap(): Map<String, Any?> = linkedMapOf(
  "heading" to heading,
  "status" to status,
  "line_count" to lineCount,
  "preview" to preview,
)

internal fun ScaffoldReviewComposition.toWireMap(): Map<String, Any?> = linkedMapOf(
  "source" to source,
  "summary" to summary,
  "baseline_layers" to baselineLayers.map { layer ->
    linkedMapOf<String, Any?>(
      "platform" to layer.platform,
      "skill" to layer.skill,
      "scope" to layer.scope,
      "required" to layer.required,
      "mode" to layer.mode,
    )
  },
)

internal fun ScaffoldExplainSkill.toWireMap(): Map<String, Any?> = linkedMapOf(
  "skill_name" to skillName,
  "content_file" to contentFile,
  "render_command" to renderCommand,
  "recommended_commands" to recommendedCommands,
)

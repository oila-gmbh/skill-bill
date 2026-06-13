package skillbill.cli.scaffold

import skillbill.cli.core.CliOutput
import skillbill.ports.scaffold.catalog.model.ScaffoldExplainResult
import skillbill.ports.scaffold.catalog.model.ScaffoldListResult
import skillbill.ports.scaffold.catalog.model.ScaffoldShowResult
import skillbill.ports.scaffold.model.ScaffoldSkillStatus
import skillbill.ports.scaffold.repo.model.ScaffoldUpgradeResult
import skillbill.ports.scaffold.repo.model.ScaffoldValidateResult
import skillbill.ports.scaffold.source.model.ScaffoldEditWithBodyFileResult
import skillbill.ports.scaffold.source.model.ScaffoldFillResult
import skillbill.ports.scaffold.source.model.ScaffoldSaveExactContentResult

/**
 * SKILL-52.3 subtask 3 — Adapter-side mappers that convert the fully typed scaffold
 * result models into the wire-shape `LinkedHashMap` payloads consumed by [CliOutput]
 * and the CliScaffoldRuntime tests.
 *
 * SKILL-52.1 subtask 3 typed only the top-level scalars and carried the rest of the
 * wire shape verbatim through an `@OpenBoundaryMap` `payload` field. SKILL-52.3
 * subtask 3 retired that field: every wire key is now rebuilt here from typed fields in
 * the EXACT producer key order the prior `skillbill.scaffold.AuthoringOperations`
 * raw-map producers emitted. The byte-equivalence contract is locked by
 * `runtime-cli/src/test/kotlin/skillbill/cli/CliScaffoldRuntimeTest.kt`
 * (field-by-field + key-order assertions) and `AuthoringOperationsTest.kt`.
 *
 * Both success and error envelope paths flow through `authoringResult { ... }` /
 * `errorResult(...)` in `ScaffoldCliCommands.kt`; errors are surfaced by the catch
 * blocks in `authoringResult` and do not pass through these mappers.
 */
internal fun ScaffoldListResult.toCliMap(): Map<String, Any?> = linkedMapOf(
  "repo_root" to repoRoot,
  "skill_count" to skillCount,
  "skills" to skills.map(ScaffoldSkillStatus::toWireMap),
)

internal fun ScaffoldShowResult.toCliMap(): Map<String, Any?> = status.toWireMap()

internal fun ScaffoldExplainResult.toCliMap(): Map<String, Any?> {
  val map = linkedMapOf<String, Any?>(
    "explanation" to explanation,
    "editable_surface" to editableSurface,
    "generated_surface" to generatedSurface,
    "governed_sidecars" to governedSidecars,
    "normal_workflow" to normalWorkflow,
    "notes" to notes,
  )
  skill?.let { map["skill"] = it.toWireMap() }
  return map
}

internal fun ScaffoldValidateResult.toCliMap(): Map<String, Any?> {
  val map = linkedMapOf<String, Any?>("repo_root" to repoRoot, "mode" to mode)
  if (mode == "selected") {
    map["skill_names"] = skillNames.orEmpty()
  }
  map["status"] = status
  map["issues"] = issues
  if (mode == "selected") {
    map["suggested_commands"] = suggestedCommands.orEmpty()
  }
  return map
}

internal fun ScaffoldUpgradeResult.toCliMap(): Map<String, Any?> = linkedMapOf(
  "repo_root" to repoRoot,
  "regenerated_count" to regeneratedCount,
  "regenerated_files" to regeneratedFiles,
  "content_md_touched" to contentMdTouched,
  "shell_ceremony_touched" to shellCeremonyTouched,
  "validator_ran" to validatorRan,
)

internal fun ScaffoldFillResult.toCliMap(): Map<String, Any?> {
  val map = LinkedHashMap<String, Any?>(status.toWireMap())
  map["wrapper_regenerated"] = wrapperRegenerated
  map["updated_section"] = updatedSection
  map["validator_ran"] = validatorRan
  return map
}

internal fun ScaffoldSaveExactContentResult.toCliMap(): Map<String, Any?> {
  val map = LinkedHashMap<String, Any?>(status.toWireMap())
  map["wrapper_regenerated"] = wrapperRegenerated
  map["updated_section"] = updatedSection
  map["validator_ran"] = validatorRan
  return map
}

internal fun ScaffoldEditWithBodyFileResult.toCliMap(): Map<String, Any?> {
  val map = linkedMapOf<String, Any?>(
    "used_editor" to usedEditor,
    "guided_sections" to guidedSections,
    "updated_section" to updatedSection,
    "validator_ran" to validatorRan,
  )
  map.putAll(status.toWireMap())
  map["wrapper_regenerated"] = wrapperRegenerated
  return map
}

package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.BaselineReviewLayerSuggestion
import skillbill.desktop.core.domain.model.ScaffoldAddOnLocationMode
import skillbill.desktop.core.domain.model.ScaffoldBaselineLayerForm
import skillbill.desktop.core.domain.model.ScaffoldBaselineLayerPayload
import skillbill.desktop.core.domain.model.ScaffoldCatalogSnapshot
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.ScaffoldPayload
import skillbill.desktop.core.domain.model.ScaffoldRunResult
import skillbill.desktop.core.domain.model.ScaffoldValidationId
import skillbill.desktop.core.domain.model.ScaffoldValidationMessage
import skillbill.desktop.core.domain.model.ScaffoldWizardFormFields
import skillbill.desktop.core.domain.model.ScaffoldWizardState

internal data class SuggestedBaselineLayer(
  val label: String,
  val form: ScaffoldBaselineLayerForm,
)

internal fun suggestedBaselineLayer(wizard: ScaffoldWizardState): SuggestedBaselineLayer? {
  if (wizard.kind != ScaffoldKind.PLATFORM_PACK) return null
  return wizard.optionCatalog.baselineReviewLayerSuggestions.firstOrNull { suggestion ->
    suggestion.matches(wizard.formFields) &&
      wizard.formFields.baselineLayers.none { layer ->
        layer.platform == suggestion.platform && layer.skill == suggestion.skill
      }
  }?.let { suggestion ->
    SuggestedBaselineLayer(
      label = suggestion.label,
      form = ScaffoldBaselineLayerForm(
        platform = suggestion.platform,
        skill = suggestion.skill,
        scope = suggestion.scope,
        required = suggestion.required,
        mode = suggestion.mode,
      ),
    )
  }
}

private fun BaselineReviewLayerSuggestion.matches(fields: ScaffoldWizardFormFields): Boolean {
  val haystack = (
    listOf(fields.platform, fields.displayName, fields.description) +
      fields.strongRoutingSignals +
      fields.tieBreakers
    )
    .joinToString(separator = " ")
    .lowercase()
  return triggerSignals.any { signal -> signal.lowercase() in haystack }
}

internal fun defaultBaselineLayer(catalog: ScaffoldCatalogSnapshot): ScaffoldBaselineLayerForm {
  val pack = catalog.baselineReviewPacks.firstOrNull()
  val skill = pack?.skills?.firstOrNull()
  return ScaffoldBaselineLayerForm(
    platform = pack?.platform.orEmpty(),
    skill = skill?.name.orEmpty(),
    mode = skill?.supportedModes?.firstOrNull().orEmpty(),
    scope = skill?.supportedScopes?.firstOrNull() ?: ScaffoldBaselineLayerForm.DEFAULT_SCOPE,
    required = true,
  )
}

internal fun isScaffoldPlanAllowed(wizard: ScaffoldWizardState): Boolean {
  val failure = wizard.executionResult as? ScaffoldRunResult.Failed ?: return true
  return failure.rollbackComplete
}

internal fun validateScaffoldWizard(wizard: ScaffoldWizardState): List<ScaffoldValidationMessage> = buildList {
  val fields = wizard.formFields
  when (wizard.kind) {
    ScaffoldKind.HORIZONTAL_SKILL -> if (fields.name.isBlank()) {
      add(ScaffoldValidationMessage(ScaffoldValidationId.SKILL_NAME_REQUIRED))
    }
    ScaffoldKind.PLATFORM_PACK -> {
      if (fields.platform.isBlank()) add(ScaffoldValidationMessage(ScaffoldValidationId.PLATFORM_SLUG_REQUIRED))
      addAll(validateBaselineLayers(wizard))
    }
    ScaffoldKind.PLATFORM_OVERRIDE_PILOTED -> {
      if (fields.platform.isBlank()) add(ScaffoldValidationMessage(ScaffoldValidationId.PLATFORM_REQUIRED))
      if (fields.family.isBlank()) add(ScaffoldValidationMessage(ScaffoldValidationId.FAMILY_REQUIRED))
    }
    ScaffoldKind.CODE_REVIEW_AREA -> {
      if (fields.platform.isBlank()) add(ScaffoldValidationMessage(ScaffoldValidationId.PLATFORM_REQUIRED))
      if (fields.area.isBlank()) add(ScaffoldValidationMessage(ScaffoldValidationId.CODE_REVIEW_AREA_REQUIRED))
    }
    ScaffoldKind.ADD_ON -> {
      if (fields.name.isBlank()) add(ScaffoldValidationMessage(ScaffoldValidationId.ADD_ON_NAME_REQUIRED))
      if (fields.platform.isBlank()) {
        add(ScaffoldValidationMessage(ScaffoldValidationId.OWNING_PLATFORM_PACK_REQUIRED))
      }
      if (fields.addonLocationMode == ScaffoldAddOnLocationMode.EXTERNAL && fields.addonLocationPath.isBlank()) {
        add(ScaffoldValidationMessage(ScaffoldValidationId.ADD_ON_LOCATION_PATH_REQUIRED))
      }
    }
    ScaffoldKind.AGENT_ADDON -> {
      if (fields.name.isBlank()) add(ScaffoldValidationMessage(ScaffoldValidationId.ADD_ON_NAME_REQUIRED))
      if (fields.description.isBlank()) add(ScaffoldValidationMessage(ScaffoldValidationId.SKILL_NAME_REQUIRED))
    }
  }
}

private fun validateBaselineLayers(wizard: ScaffoldWizardState): List<ScaffoldValidationMessage> = buildList {
  val newPlatform = wizard.formFields.platform.trim()
  val catalog = wizard.optionCatalog
  val packsBySlug = catalog.baselineReviewPacks.associateBy { it.platform }
  val seen = mutableSetOf<Pair<String, String>>()

  wizard.formFields.baselineLayers.forEachIndexed { index, layer ->
    val layerNumber = (index + 1).toString()
    val platform = layer.platform.trim()
    val skillName = layer.skill.trim()
    if (platform.isBlank()) {
      add(ScaffoldValidationMessage(ScaffoldValidationId.BASELINE_PACK_REQUIRED, listOf(layerNumber)))
      return@forEachIndexed
    }
    val pack = packsBySlug[platform]
    if (pack == null) {
      add(ScaffoldValidationMessage(ScaffoldValidationId.BASELINE_PACK_UNAVAILABLE, listOf(layerNumber, platform)))
      return@forEachIndexed
    }
    if (skillName.isBlank()) {
      add(ScaffoldValidationMessage(ScaffoldValidationId.BASELINE_SKILL_REQUIRED, listOf(layerNumber)))
      return@forEachIndexed
    }
    val skill = pack.skills.firstOrNull { it.name == skillName }
    if (skill == null) {
      add(
        ScaffoldValidationMessage(
          ScaffoldValidationId.BASELINE_SKILL_UNAVAILABLE,
          listOf(layerNumber, skillName, platform),
        ),
      )
      return@forEachIndexed
    }
    if (layer.mode !in skill.supportedModes) {
      add(
        ScaffoldValidationMessage(
          ScaffoldValidationId.BASELINE_MODE_UNSUPPORTED,
          listOf(layerNumber, layer.mode, platform, skillName),
        ),
      )
    }
    if (layer.scope !in skill.supportedScopes) {
      add(
        ScaffoldValidationMessage(
          ScaffoldValidationId.BASELINE_SCOPE_UNSUPPORTED,
          listOf(layerNumber, layer.scope, platform, skillName),
        ),
      )
    }
    if (!seen.add(platform to skillName)) {
      add(
        ScaffoldValidationMessage(
          ScaffoldValidationId.DUPLICATE_BASELINE_LAYER,
          listOf(layerNumber, platform, skillName),
        ),
      )
    }
    if (newPlatform.isNotBlank() && platform == newPlatform) {
      add(ScaffoldValidationMessage(ScaffoldValidationId.BASELINE_SELF_REFERENCE, listOf(layerNumber, newPlatform)))
    } else if (newPlatform.isNotBlank() && compositionPathExists(catalog, from = platform, to = newPlatform)) {
      add(
        ScaffoldValidationMessage(
          ScaffoldValidationId.BASELINE_COMPOSITION_CYCLE,
          listOf(layerNumber, newPlatform, platform),
        ),
      )
    }
  }
}

private fun compositionPathExists(catalog: ScaffoldCatalogSnapshot, from: String, to: String): Boolean {
  val graph = catalog.baselineReviewCompositionEdges.groupBy(
    keySelector = { edge -> edge.sourcePlatform },
    valueTransform = { edge -> edge.targetPlatform },
  )
  val visited = mutableSetOf<String>()
  fun visit(platform: String): Boolean {
    if (!visited.add(platform)) return false
    if (platform == to) return true
    return graph[platform].orEmpty().any(::visit)
  }
  return visit(from)
}

internal fun buildScaffoldPayload(wizard: ScaffoldWizardState, repoRoot: String?): ScaffoldPayload? {
  val fields = wizard.formFields
  val root = repoRoot?.takeIf { it.isNotBlank() } ?: return null
  return when (wizard.kind) {
    ScaffoldKind.HORIZONTAL_SKILL -> if (fields.name.isBlank()) {
      null
    } else {
      val trimmed = fields.name.trim()
      val normalized = if (trimmed.startsWith("bill-")) trimmed else "bill-$trimmed"
      ScaffoldPayload.HorizontalSkill(
        repoRoot = root,
        name = normalized,
        description = fields.description.trim(),
        contentBody = fields.contentBody.takeIf { it.isNotBlank() },
        subagentSpecialists = fields.subagentSpecialists.filter(String::isNotBlank),
        suppressSubagents = fields.suppressSubagents,
      )
    }
    ScaffoldKind.PLATFORM_PACK -> if (fields.platform.isBlank()) {
      null
    } else {
      ScaffoldPayload.PlatformPack(
        repoRoot = root,
        platform = fields.platform.trim(),
        displayName = fields.displayName.trim(),
        description = fields.description.trim(),
        strongRoutingSignals = fields.strongRoutingSignals.filter(String::isNotBlank),
        tieBreakers = fields.tieBreakers.filter(String::isNotBlank),
        baselineLayers = fields.baselineLayers.map { layer ->
          ScaffoldBaselineLayerPayload(
            platform = layer.platform.trim(),
            skill = layer.skill.trim(),
            scope = layer.scope.trim(),
            required = layer.required,
            mode = layer.mode.trim(),
          )
        },
        subagentSpecialists = fields.subagentSpecialists.filter(String::isNotBlank),
        suppressSubagents = fields.suppressSubagents,
        contentBody = fields.contentBody.takeIf { it.isNotBlank() },
      )
    }
    ScaffoldKind.PLATFORM_OVERRIDE_PILOTED -> if (
      fields.platform.isBlank() || fields.family.isBlank()
    ) {
      null
    } else {
      ScaffoldPayload.PlatformOverride(
        repoRoot = root,
        platform = fields.platform.trim(),
        family = fields.family.trim(),
        description = fields.description.trim(),
        contentBody = fields.contentBody.takeIf { it.isNotBlank() },
        subagentSpecialists = fields.subagentSpecialists.filter(String::isNotBlank),
        suppressSubagents = fields.suppressSubagents,
      )
    }
    ScaffoldKind.CODE_REVIEW_AREA -> if (
      fields.platform.isBlank() || fields.area.isBlank()
    ) {
      null
    } else {
      ScaffoldPayload.CodeReviewArea(
        repoRoot = root,
        platform = fields.platform.trim(),
        area = fields.area.trim(),
        description = fields.description.trim(),
        contentBody = fields.contentBody.takeIf { it.isNotBlank() },
      )
    }
    ScaffoldKind.ADD_ON -> if (fields.name.isBlank() || fields.platform.isBlank()) {
      null
    } else {
      ScaffoldPayload.AddOn(
        repoRoot = root,
        name = fields.name.trim(),
        platform = fields.platform.trim(),
        description = fields.description.trim(),
        addonLocationPath = fields.addonLocationPath.trim()
          .takeIf { fields.addonLocationMode == ScaffoldAddOnLocationMode.EXTERNAL && it.isNotBlank() },
      )
    }
    ScaffoldKind.AGENT_ADDON -> if (fields.name.isBlank() || fields.description.isBlank()) {
      null
    } else {
      ScaffoldPayload.AgentAddon(
        repoRoot = root,
        slug = fields.name.trim(),
        description = fields.description.trim(),
        agentIds = listOf("codex"),
        consumers = listOf("bill-feature"),
        contentBody = fields.contentBody.takeIf { it.isNotBlank() },
      )
    }
  }
}

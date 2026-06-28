package skillbill.desktop.core.domain.model

/**
 * View-state for the scaffold wizard surface. Exists alongside [SkillBillState] so the existing
 * mega-state remains source-of-truth for everything else while wizards are toggled in/out via a
 * single nullable slot (`SkillBillState.scaffoldWizard`).
 *
 * Field meanings:
 * - [kind] is the wizard variant under edit; mirror the runtime's supported kinds 1:1.
 * - [formFields] is the typed, freeform form payload the user is filling in. Wizards write to
 *   this via [ScaffoldWizardFormUpdate] commands; the view model never interprets individual
 *   keys, it just stores them so the wizard composable can re-render.
 * - [optionCatalog] is the static option list (areas, presets, families) and the discovered
 *   piloted-pack list. Captured at wizard-open time so the dialog renders against a stable list.
 * - [dryRunPreview] is the most recently planned scaffold (Plan button). Reset to null whenever
 *   the form mutates, so Run can never apply a stale preview.
 * - [executionResult] holds the most recently returned ScaffoldRunResult (Success/Failed) for
 *   execute mode. Used to surface the success banner or the runtime-exception console.
 * - [dirtyRepoWarning] is whether the workspace currently has any non-generated changes; the
 *   wizard surfaces a warning banner until the user toggles [overrideDirtyRepo].
 * - [busy] is true while a dry-run or execute call is in flight.
 * - [overrideDirtyRepo] is the user's explicit acknowledgement that they are scaffolding into a
 *   dirty repo. Required (when [dirtyRepoWarning] is true) before Run is enabled.
 */
data class ScaffoldWizardState(
  val kind: ScaffoldKind,
  val formFields: ScaffoldWizardFormFields = ScaffoldWizardFormFields(),
  val optionCatalog: ScaffoldCatalogSnapshot = ScaffoldCatalogSnapshot.empty,
  val dryRunPreview: ScaffoldPlan? = null,
  val executionResult: ScaffoldRunResult? = null,
  val validationErrors: List<ScaffoldValidationMessage> = emptyList(),
  val baselineLayerSuggestion: ScaffoldBaselineLayerForm? = null,
  val baselineLayerSuggestionLabel: String? = null,
  val dirtyRepoWarning: Boolean = false,
  val overrideDirtyRepo: Boolean = false,
  val busy: Boolean = false,
) {
  /** Run is gated by a successful Plan + dirty-repo acknowledgement (or a clean repo). AC2/AC8. */
  val runEnabled: Boolean
    get() = !busy &&
      dryRunPreview != null &&
      (!dirtyRepoWarning || overrideDirtyRepo) &&
      executionResult !is ScaffoldRunResult.Success
}

/**
 * Freeform string-bag for wizard form fields. Strongly-typed projection lives in the wizard's
 * payload builder; the bag is enough state to drive Compose text fields and dropdowns without
 * exploding the surface area of [ScaffoldWizardState].
 *
 * Field naming intentionally matches the runtime SCAFFOLD_PAYLOAD.md fields so the wizard form
 * trivially maps onto [ScaffoldPayload] sub-types in the view model's `buildPayload()` helper.
 */
data class ScaffoldWizardFormFields(
  val name: String = "",
  val description: String = "",
  val platform: String = "",
  val family: String = "",
  val area: String = "",
  val displayName: String = "",
  val strongRoutingSignals: List<String> = emptyList(),
  val tieBreakers: List<String> = emptyList(),
  val baselineLayers: List<ScaffoldBaselineLayerForm> = emptyList(),
  val subagentSpecialists: List<String> = emptyList(),
  val suppressSubagents: Boolean = false,
  val contentBody: String = "",
)

data class ScaffoldBaselineLayerForm(
  val rowId: Long = 0L,
  val platform: String = "",
  val skill: String = "",
  val scope: String = DEFAULT_SCOPE,
  val required: Boolean = true,
  val mode: String = "",
) {
  companion object {
    const val DEFAULT_SCOPE: String = "same-review-scope"
  }
}

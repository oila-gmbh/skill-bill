package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.ScaffoldBaselineLayerForm
import skillbill.desktop.core.domain.model.ScaffoldCatalogSnapshot
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.ScaffoldOutcome
import skillbill.desktop.core.domain.model.ScaffoldRunResult
import skillbill.desktop.core.domain.model.ScaffoldWizardFormFields
import skillbill.desktop.core.domain.model.ScaffoldWizardState
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.service.RuntimeScaffoldGateway

internal class SkillBillScaffoldController(
  private val state: SkillBillViewState,
  private val scaffoldGateway: RuntimeScaffoldGateway,
) {
  fun openScaffoldWizard(kind: ScaffoldKind, snapshot: ScaffoldCatalogSnapshot): SkillBillState = with(state) {
    if (!canStartScaffoldAction() || !kind.creationSupported) {
      return currentState
    }
    scaffoldWizard = ScaffoldWizardState(
      kind = kind,
      formFields = ScaffoldWizardFormFields(),
      optionCatalog = snapshot,
      dryRunPreview = null,
      executionResult = null,
      validationErrors = emptyList(),
      dirtyRepoWarning = false,
      overrideDirtyRepo = false,
      busy = false,
    )
    currentState = createState()
    currentState
  }

  fun canOpenScaffoldWizard(): Boolean = canStartScaffoldAction()

  fun beginOpenScaffoldWizard(kind: ScaffoldKind): ScaffoldCatalogRequest? = with(state) {
    if (!canStartScaffoldAction() || !kind.creationSupported) {
      return null
    }
    ScaffoldCatalogRequest(kind = kind, session = currentSession)
  }

  suspend fun runOpenScaffoldWizard(request: ScaffoldCatalogRequest): ScaffoldCatalogResponse {
    val snapshot = scaffoldGateway.catalogSnapshot(request.session)
    return ScaffoldCatalogResponse(kind = request.kind, snapshot = snapshot)
  }

  fun finishOpenScaffoldWizard(response: ScaffoldCatalogResponse): SkillBillState =
    openScaffoldWizard(response.kind, response.snapshot)

  suspend fun fetchScaffoldCatalogSnapshot(): ScaffoldCatalogSnapshot =
    scaffoldGateway.catalogSnapshot(state.currentSession)

  fun selectScaffoldWizardKind(kind: ScaffoldKind): SkillBillState = with(state) {
    val current = scaffoldWizard ?: return currentState
    if (current.busy || !kind.creationSupported) {
      return currentState
    }
    if (current.kind == kind) {
      return currentState
    }
    val partialMutationLock = (current.executionResult as? ScaffoldRunResult.Failed)
      ?.let { !it.rollbackComplete } == true
    if (partialMutationLock) {
      return currentState
    }
    activeScaffoldToken += 1
    if (busyOperation == SkillBillBusyOperation.SCAFFOLD) {
      busyOperation = null
    }
    scaffoldWizard = current.copy(
      kind = kind,
      formFields = ScaffoldWizardFormFields(),
      dryRunPreview = null,
      executionResult = null,
      validationErrors = emptyList(),
      overrideDirtyRepo = false,
      busy = false,
    )
    currentState = createState()
    currentState
  }

  fun updateScaffoldForm(transform: (ScaffoldWizardFormFields) -> ScaffoldWizardFormFields): SkillBillState =
    with(state) {
      val current = scaffoldWizard ?: return currentState
      if (current.busy) {
        return currentState
      }
      val updatedFields = transform(current.formFields)
      if (updatedFields == current.formFields) {
        return currentState
      }
      scaffoldWizard = current.copy(
        formFields = updatedFields,
        dryRunPreview = null,
        validationErrors = emptyList(),
        executionResult = current.executionResult.takeIf { it is ScaffoldRunResult.Failed },
      )
      currentState = createState()
      currentState
    }

  fun addScaffoldBaselineLayer(layer: ScaffoldBaselineLayerForm? = null): SkillBillState = with(state) {
    val current = scaffoldWizard ?: return currentState
    if (current.busy || current.kind != ScaffoldKind.PLATFORM_PACK) {
      return currentState
    }
    val nextLayer = ensureBaselineLayerRowId(layer ?: defaultBaselineLayer(current.optionCatalog))
    updateScaffoldForm { fields ->
      fields.copy(baselineLayers = fields.baselineLayers + nextLayer)
    }
  }

  fun editScaffoldBaselineLayer(
    index: Int,
    transform: (ScaffoldBaselineLayerForm) -> ScaffoldBaselineLayerForm,
  ): SkillBillState = with(state) {
    val current = scaffoldWizard ?: return currentState
    if (
      current.busy ||
      current.kind != ScaffoldKind.PLATFORM_PACK ||
      index !in current.formFields.baselineLayers.indices
    ) {
      return currentState
    }
    updateScaffoldForm { fields ->
      fields.copy(
        baselineLayers = fields.baselineLayers.mapIndexed { layerIndex, layer ->
          if (layerIndex == index) transform(layer) else layer
        },
      )
    }
  }

  fun removeScaffoldBaselineLayer(index: Int): SkillBillState = with(state) {
    val current = scaffoldWizard ?: return currentState
    if (
      current.busy ||
      current.kind != ScaffoldKind.PLATFORM_PACK ||
      index !in current.formFields.baselineLayers.indices
    ) {
      return currentState
    }
    updateScaffoldForm { fields ->
      fields.copy(baselineLayers = fields.baselineLayers.filterIndexed { layerIndex, _ -> layerIndex != index })
    }
  }

  fun addSuggestedScaffoldBaselineLayer(): SkillBillState = with(state) {
    val current = scaffoldWizard ?: return currentState
    if (current.busy || current.kind != ScaffoldKind.PLATFORM_PACK) {
      return currentState
    }
    val suggestion = suggestedBaselineLayer(current)?.form ?: return currentState
    addScaffoldBaselineLayer(suggestion)
  }

  private fun ensureBaselineLayerRowId(layer: ScaffoldBaselineLayerForm): ScaffoldBaselineLayerForm =
    if (layer.rowId != 0L) {
      layer
    } else {
      layer.copy(rowId = state.nextScaffoldBaselineLayerRowId++)
    }

  fun setScaffoldDirtyOverride(override: Boolean): SkillBillState = with(state) {
    val current = scaffoldWizard ?: return currentState
    if (current.overrideDirtyRepo == override) {
      return currentState
    }
    scaffoldWizard = current.copy(overrideDirtyRepo = override)
    currentState = createState()
    currentState
  }

  fun dismissScaffoldWizard(): SkillBillState = with(state) {
    activeScaffoldToken += 1
    if (busyOperation == SkillBillBusyOperation.SCAFFOLD) {
      busyOperation = null
      activeOperationToken += 1
    }
    scaffoldWizard = null
    currentState = createState()
    currentState
  }

  fun acknowledgeScaffoldFailure(): SkillBillState = with(state) {
    val current = scaffoldWizard ?: return currentState
    scaffoldWizard = current.copy(executionResult = null)
    currentState = createState()
    currentState
  }

  fun beginScaffoldDryRun(): ScaffoldRunRequest? = with(state) {
    val current = scaffoldWizard ?: return null
    if (current.busy || !canStartScaffoldAction()) {
      return null
    }
    if (!isScaffoldPlanAllowed(current)) {
      return null
    }
    val validationErrors = validateScaffoldWizard(current)
    if (validationErrors.isNotEmpty()) {
      failScaffoldFormValidation(current, validationErrors)
      return null
    }
    val payload = buildScaffoldPayload(current, currentSession?.repoPath) ?: return null
    activeScaffoldToken += 1
    scaffoldWizard = current.copy(busy = true, dryRunPreview = null, executionResult = null)
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.SCAFFOLD
    currentState = createState()
    ScaffoldRunRequest(
      token = activeScaffoldToken,
      payload = payload,
      mode = ScaffoldRunMode.DRY_RUN,
    )
  }

  suspend fun runScaffoldDryRun(request: ScaffoldRunRequest): ScaffoldRunResult =
    scaffoldGateway.dryRun(request.payload)

  fun finishScaffoldDryRun(request: ScaffoldRunRequest, result: ScaffoldRunResult): SkillBillState = with(state) {
    if (request.token != activeScaffoldToken) {
      if (busyOperation == SkillBillBusyOperation.SCAFFOLD) {
        busyOperation = null
      }
      currentState = createState()
      return currentState
    }
    val current = scaffoldWizard ?: return currentState
    busyOperation = null
    scaffoldWizard = when (result) {
      is ScaffoldRunResult.Preview -> current.copy(
        busy = false,
        dryRunPreview = result.planned,
        executionResult = null,
      )
      is ScaffoldRunResult.Failed -> current.copy(
        busy = false,
        dryRunPreview = null,
        executionResult = result,
      )
      is ScaffoldRunResult.Success -> current.copy(
        busy = false,
        dryRunPreview = null,
        executionResult = ScaffoldRunResult.Failed(
          exceptionName = "IllegalDryRunResponse",
          exceptionMessage = "Runtime returned Success for dry-run mode.",
          rollbackComplete = true,
        ),
      )
    }
    currentState = createState()
    currentState
  }

  fun beginScaffoldExecute(): ScaffoldRunRequest? = with(state) {
    val current = scaffoldWizard ?: return null
    if (!current.runEnabled || !canStartScaffoldAction()) {
      return null
    }
    val validationErrors = validateScaffoldWizard(current)
    if (validationErrors.isNotEmpty()) {
      failScaffoldFormValidation(current, validationErrors)
      return null
    }
    val payload = buildScaffoldPayload(current, currentSession?.repoPath) ?: return null
    activeScaffoldToken += 1
    scaffoldWizard = current.copy(busy = true, executionResult = null)
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.SCAFFOLD
    currentState = createState()
    ScaffoldRunRequest(
      token = activeScaffoldToken,
      payload = payload,
      mode = ScaffoldRunMode.EXECUTE,
    )
  }

  suspend fun runScaffoldExecute(request: ScaffoldRunRequest): ScaffoldRunResult =
    scaffoldGateway.execute(request.payload)

  fun finishScaffoldExecute(request: ScaffoldRunRequest, result: ScaffoldRunResult): SkillBillState = with(state) {
    if (request.token != activeScaffoldToken) {
      if (busyOperation == SkillBillBusyOperation.SCAFFOLD) {
        busyOperation = null
      }
      currentState = createState()
      return currentState
    }
    val current = scaffoldWizard ?: return currentState
    busyOperation = null
    scaffoldWizard = when (result) {
      is ScaffoldRunResult.Success -> current.copy(
        busy = false,
        executionResult = result,
        dryRunPreview = null,
      )
      is ScaffoldRunResult.Failed -> current.copy(
        busy = false,
        executionResult = result,
        dryRunPreview = null,
      )
      is ScaffoldRunResult.Preview -> current.copy(
        busy = false,
        executionResult = ScaffoldRunResult.Failed(
          exceptionName = "IllegalExecuteResponse",
          exceptionMessage = "Runtime returned Preview for execute mode.",
          rollbackComplete = true,
        ),
        dryRunPreview = null,
      )
    }
    currentState = createState()
    currentState
  }

  fun resolveAuthoredTreeItemForScaffold(outcome: ScaffoldOutcome): String? = with(state) {
    val skillPath = outcome.skillPath.takeIf { it.isNotBlank() } ?: return null
    val authoredCandidates = outcome.createdFiles.filterNot { it.endsWith("SKILL.md") }
    val needle = skillPath.trimEnd('/')
    treeItems.flatten()
      .filter { it.kind != TreeItemKind.GENERATED_ARTIFACT }
      .firstOrNull { item ->
        val authored = item.authoredPath
        when {
          authored == null -> false
          authoredCandidates.any { it.endsWith(authored) } -> true
          authored.contains(needle) -> true
          else -> false
        }
      }
      ?.id
  }

  fun isScaffoldPlanAllowed(): Boolean = state.scaffoldWizard?.let { isScaffoldPlanAllowed(it) } ?: true

  private fun canStartScaffoldAction(): Boolean = state.busyOperation == null

  private fun failScaffoldFormValidation(current: ScaffoldWizardState, errors: List<String>) = with(state) {
    scaffoldWizard = current.copy(
      dryRunPreview = null,
      validationErrors = errors,
      executionResult = null,
    )
    currentState = createState()
  }
}

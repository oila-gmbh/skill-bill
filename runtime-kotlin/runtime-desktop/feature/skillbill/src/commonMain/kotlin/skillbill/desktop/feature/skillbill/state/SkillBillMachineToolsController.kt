package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.MachineSkillHealthFilter
import skillbill.desktop.core.domain.model.MachineSkillInstallState
import skillbill.desktop.core.domain.model.MachineSkillInstallStep
import skillbill.desktop.core.domain.model.MachineSkillManagerState
import skillbill.desktop.core.domain.model.MachineSkillOwnershipFilter
import skillbill.desktop.core.domain.model.MachineSkillSourceSummary
import skillbill.desktop.core.domain.model.MachineSkillManagerDetail
import skillbill.desktop.core.domain.model.MachineSkillManagerRow
import skillbill.desktop.core.domain.model.MachineSkillPreviewLine
import skillbill.desktop.core.domain.model.MachineSkillTargetResult
import skillbill.desktop.core.domain.model.MachineSkillTargetOption
import skillbill.desktop.core.domain.model.MachineToolAction
import skillbill.desktop.core.domain.model.MachineToolsState
import skillbill.desktop.core.domain.model.MachineToolsSurface

internal class SkillBillMachineToolsController(private val state: SkillBillViewState) {
  private var sourceToken = 0L
  private var previewToken = 0L
  private var inventoryToken = 0L
  fun dispatch(action: MachineToolAction) {
    invalidatePendingCompletions()
    update {
      when (action) {
        MachineToolAction.OPEN_CATALOG -> copy(surface = MachineToolsSurface.CATALOG)
        MachineToolAction.INSTALL_SKILL -> copy(surface = MachineToolsSurface.INSTALL)
        MachineToolAction.MANAGE_SKILLS -> copy(surface = MachineToolsSurface.MANAGER)
      }
    }
  }

  fun dismiss() {
    invalidatePendingCompletions()
    update { copy(surface = null) }
  }

  fun beginSourceInspection(): Long = ++sourceToken

  fun sourceInspected(
    token: Long,
    source: MachineSkillSourceSummary,
    targets: List<MachineSkillTargetOption>,
  ) {
    if (token == sourceToken) sourceInspected(source, targets)
  }

  fun sourceInspected(source: MachineSkillSourceSummary, targets: List<MachineSkillTargetOption>) = update {
    previewToken++
    copy(
      install = MachineSkillInstallState(
        step = MachineSkillInstallStep.SOURCE,
        source = source,
        targets = targets.map { it.copy(selected = it.detected && it.conflict == null) },
      ),
    )
  }

  fun toggleTarget(id: String) {
    previewToken++
    update {
      copy(install = install.copy(
        targets = install.targets.map { target ->
          if (target.id == id && target.conflict == null) target.copy(selected = !target.selected) else target
        },
        planId = null,
        preview = emptyList(),
      ))
    }
  }

  fun setInstallStep(step: MachineSkillInstallStep) {
    if (step != MachineSkillInstallStep.PREVIEW) previewToken++
    update { copy(install = install.copy(step = step)) }
  }

  fun beginPreview(): Long = ++previewToken

  fun previewReady(token: Long, planId: String, preview: List<MachineSkillPreviewLine>, warnings: List<String>) {
    if (token != previewToken) return
    update { copy(install = install.copy(step = MachineSkillInstallStep.PREVIEW, planId = planId, preview = preview, warnings = warnings)) }
  }

  fun mutationFinished(results: List<MachineSkillTargetResult>, postMortem: String?) = update {
    copy(
      machineMutationBusy = false,
      postMortem = postMortem,
      install = install.copy(step = MachineSkillInstallStep.RESULTS, results = results),
    )
  }

  fun beginInventoryRefresh(): Long {
    val token = ++inventoryToken
    update { copy(manager = manager.copy(loading = true, error = null)) }
    return token
  }

  fun inventoryRefreshed(
    token: Long,
    rows: List<MachineSkillManagerRow>,
    selectedDetail: MachineSkillManagerDetail?,
  ) {
    if (token != inventoryToken) return
    update { copy(manager = manager.copy(rows = rows, detail = selectedDetail, loading = false)) }
  }

  fun updateManagerQuery(query: String) = update { copy(manager = manager.copy(query = query)) }
  fun updateOwnershipFilter(filter: MachineSkillOwnershipFilter) = update {
    copy(manager = manager.copy(ownershipFilter = filter))
  }
  fun updateHealthFilter(filter: MachineSkillHealthFilter) = update {
    copy(manager = manager.copy(healthFilter = filter))
  }
  fun selectManagerSkill(name: String) {
    inventoryToken++
    update { copy(manager = manager.copy(selectedName = name, detail = null)) }
  }

  fun beginMutation(): Boolean {
    if (state.machineTools.machineMutationBusy) return false
    update { copy(machineMutationBusy = true) }
    return true
  }

  fun finishMutation() = update { copy(machineMutationBusy = false) }
  fun acknowledgePostMortem() = update { copy(postMortem = null) }

  private fun invalidatePendingCompletions() {
    sourceToken++
    previewToken++
    inventoryToken++
  }

  private fun update(transform: MachineToolsState.() -> MachineToolsState) {
    state.machineTools = state.machineTools.transform()
    state.currentState = state.createState()
  }
}

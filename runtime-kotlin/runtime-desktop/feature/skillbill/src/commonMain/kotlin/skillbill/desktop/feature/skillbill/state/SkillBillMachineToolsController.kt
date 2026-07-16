package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.MachineSkillHealthFilter
import skillbill.desktop.core.domain.model.MachineSkillInstallState
import skillbill.desktop.core.domain.model.MachineSkillInstallStep
import skillbill.desktop.core.domain.model.MachineSkillManagerDetail
import skillbill.desktop.core.domain.model.MachineSkillManagerRow
import skillbill.desktop.core.domain.model.MachineSkillOwnershipFilter
import skillbill.desktop.core.domain.model.MachineSkillPreviewLine
import skillbill.desktop.core.domain.model.MachineSkillSourceSummary
import skillbill.desktop.core.domain.model.MachineSkillTargetOption
import skillbill.desktop.core.domain.model.MachineSkillTargetResult
import skillbill.desktop.core.domain.model.MachineToolAction
import skillbill.desktop.core.domain.model.MachineToolsState
import skillbill.desktop.core.domain.model.MachineToolsSurface

internal class SkillBillMachineToolsController(private val state: SkillBillViewState) {
  private var sourceToken = 0L
  private var previewToken = 0L
  private var inventoryToken = 0L
  private var inventoryDetails: Map<String, MachineSkillManagerDetail> = emptyMap()
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

  fun beginSourceInspection(): Long {
    val token = ++sourceToken
    previewToken++
    update { copy(install = MachineSkillInstallState(step = MachineSkillInstallStep.SOURCE)) }
    return token
  }

  fun sourceInspected(token: Long, source: MachineSkillSourceSummary, targets: List<MachineSkillTargetOption>) {
    if (token == sourceToken) sourceInspected(source, targets)
  }

  fun sourceInspected(source: MachineSkillSourceSummary, targets: List<MachineSkillTargetOption>) = update {
    previewToken++
    copy(
      install = MachineSkillInstallState(
        step = MachineSkillInstallStep.SOURCE,
        source = source,
        targets = targets.map { it.copy(selected = it.detected && it.conflict == null) },
        error = null,
      ),
    )
  }

  fun sourceFailed(token: Long, message: String) {
    if (token == sourceToken) {
      update {
        copy(install = MachineSkillInstallState(step = MachineSkillInstallStep.SOURCE, error = message))
      }
    }
  }

  fun toggleTarget(id: String) {
    previewToken++
    update {
      copy(
        install = install.copy(
          targets = install.targets.map { target ->
            if (target.id == id && target.conflict == null) target.copy(selected = !target.selected) else target
          },
          planId = null,
          preview = emptyList(),
        ),
      )
    }
  }

  fun setInstallStep(step: MachineSkillInstallStep) {
    if (step != MachineSkillInstallStep.PREVIEW) previewToken++
    update {
      copy(
        install = install.copy(
          step = step,
          error = null,
          results = if (step == MachineSkillInstallStep.TARGETS) emptyList() else install.results,
          planId = if (step == MachineSkillInstallStep.TARGETS) null else install.planId,
          preview = if (step == MachineSkillInstallStep.TARGETS) emptyList() else install.preview,
        ),
      )
    }
  }

  fun beginPreview(): Long = ++previewToken

  fun previewReady(token: Long, planId: String, preview: List<MachineSkillPreviewLine>, warnings: List<String>) {
    if (token != previewToken) return
    update {
      copy(
        install = install.copy(
          step = MachineSkillInstallStep.PREVIEW,
          planId = planId,
          preview = preview,
          warnings = warnings,
          error = null,
        ),
      )
    }
  }

  fun previewFailed(token: Long, message: String) {
    if (token == previewToken) update { copy(install = install.copy(error = message, planId = null)) }
  }

  fun mutationFinished(results: List<MachineSkillTargetResult>, postMortem: String?) = update {
    copy(
      machineMutationBusy = false,
      postMortem = postMortem,
      install = install.copy(step = MachineSkillInstallStep.RESULTS, results = results, error = null),
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
    details: Map<String, MachineSkillManagerDetail> = emptyMap(),
  ) {
    if (token != inventoryToken) return
    inventoryDetails = details
    update { copy(manager = manager.copy(rows = rows, detail = selectedDetail, loading = false)) }
  }

  fun inventoryFailed(token: Long, message: String) {
    if (token == inventoryToken) update { copy(manager = manager.copy(loading = false, error = message)) }
  }

  fun mutationFailed(message: String) = update {
    copy(machineMutationBusy = false, install = install.copy(step = MachineSkillInstallStep.RESULTS, error = message))
  }

  fun updateManagerQuery(query: String) = update { copy(manager = manager.copy(query = query)) }
  fun updateOwnershipFilter(filter: MachineSkillOwnershipFilter) = update {
    copy(manager = manager.copy(ownershipFilter = filter))
  }
  fun updateHealthFilter(filter: MachineSkillHealthFilter) = update {
    copy(manager = manager.copy(healthFilter = filter))
  }
  fun updateAgentFilter(agent: String?) = update { copy(manager = manager.copy(agentFilter = agent)) }
  fun beginManagerAction(action: String) = update {
    val selected = manager.detail?.targets.orEmpty().filter { it.state == "PRESENT" }.map { it.id }.toSet()
    copy(
      manager = manager.copy(
        pendingAction = action,
        authoritativeSource = null,
        replacementTargetIds = if (action == "MANAGE_AGENTS") selected else emptySet(),
        actionPlanId = null,
        actionPreview = emptyList(),
        error = null,
      ),
    )
  }
  fun selectAuthoritativeSource(path: String) = update {
    copy(manager = manager.copy(authoritativeSource = path, actionPlanId = null, actionPreview = emptyList()))
  }
  fun toggleManagerTarget(id: String) = update {
    val selected = manager.replacementTargetIds
    copy(
      manager = manager.copy(
        replacementTargetIds = if (id in selected) selected - id else selected + id,
        actionPlanId = null,
        actionPreview = emptyList(),
      ),
    )
  }
  fun managerPreviewReady(planId: String, preview: List<MachineSkillPreviewLine>) = update {
    copy(manager = manager.copy(actionPlanId = planId, actionPreview = preview, error = null))
  }
  fun managerActionFailed(message: String) = update {
    copy(machineMutationBusy = false, manager = manager.copy(error = message))
  }
  fun managerActionFinished() = update {
    copy(
      machineMutationBusy = false,
      manager = manager.copy(
        pendingAction = null,
        authoritativeSource = null,
        replacementTargetIds = emptySet(),
        actionPlanId = null,
        actionPreview = emptyList(),
      ),
    )
  }
  fun selectManagerSkill(name: String) {
    inventoryToken++
    update { copy(manager = manager.copy(selectedName = name, detail = inventoryDetails[name])) }
  }

  fun beginMutation(): Boolean {
    if (state.machineTools.machineMutationBusy) return false
    update {
      copy(
        machineMutationBusy = true,
        install = install.copy(step = MachineSkillInstallStep.APPLYING, error = null),
      )
    }
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

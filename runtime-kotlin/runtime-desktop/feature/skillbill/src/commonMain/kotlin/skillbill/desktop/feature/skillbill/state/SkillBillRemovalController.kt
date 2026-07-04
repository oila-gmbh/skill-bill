package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.ConfirmDeletionState
import skillbill.desktop.core.domain.model.DesktopSkillRemovalRequest
import skillbill.desktop.core.domain.model.DesktopSkillRemovalResult
import skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget
import skillbill.desktop.core.domain.model.PartialMutationPostMortem
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.service.RuntimeSkillRemoveGateway

internal class SkillBillRemovalController(
  private val state: SkillBillViewState,
  private val skillRemoveGateway: RuntimeSkillRemoveGateway,
) {
  fun showConfirmDeletion(target: DesktopSkillRemovalTarget): SkillBillState = with(state) {
    if (confirmDeletion != null || busyOperation != null) {
      return currentState
    }
    activeRemovalToken += 1
    confirmDeletion = ConfirmDeletionState(target = target)
    currentState = createState()
    currentState
  }

  fun dismissConfirmDeletion(): SkillBillState = with(state) {
    activeRemovalToken += 1
    if (busyOperation == SkillBillBusyOperation.DELETE) {
      busyOperation = null
      activeOperationToken += 1
    }
    confirmDeletion = null
    currentState = createState()
    currentState
  }

  fun setRemovalAcknowledged(acknowledged: Boolean): SkillBillState = with(state) {
    val current = confirmDeletion ?: return currentState
    confirmDeletion = current.copy(acknowledged = acknowledged)
    currentState = createState()
    currentState
  }

  fun acknowledgeRemovalFailure(): SkillBillState = with(state) {
    partialMutationPostMortem = null
    val current = confirmDeletion
    if (current != null) {
      confirmDeletion = current.copy(
        executionResult = null,
        partialMutationLocked = false,
      )
    }
    currentState = createState()
    currentState
  }

  fun beginPreviewRemoval(): SkillRemovalRunRequest? = with(state) {
    val current = confirmDeletion ?: return null
    if (current.previewBusy || current.executeBusy || current.partialMutationLocked) {
      return null
    }
    if (busyOperation != null) {
      return null
    }
    val repoRoot = currentSession?.repoPath?.takeIf { it.isNotBlank() } ?: return null
    activeRemovalToken += 1
    confirmDeletion = current.copy(previewBusy = true, preview = null, executionResult = null)
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.DELETE
    currentState = createState()
    SkillRemovalRunRequest(
      token = activeRemovalToken,
      payload = DesktopSkillRemovalRequest(target = current.target, repoRootAbsolutePath = repoRoot),
    )
  }

  suspend fun runPreviewRemoval(request: SkillRemovalRunRequest): DesktopSkillRemovalResult =
    skillRemoveGateway.preview(request.payload)

  fun finishPreviewRemoval(request: SkillRemovalRunRequest, result: DesktopSkillRemovalResult): SkillBillState =
    with(state) {
      if (request.token != activeRemovalToken) {
        if (busyOperation == SkillBillBusyOperation.DELETE) {
          busyOperation = null
        }
        currentState = createState()
        return currentState
      }
      val current = confirmDeletion ?: return currentState
      busyOperation = null
      confirmDeletion = when (result) {
        is DesktopSkillRemovalResult.Preview -> current.copy(
          previewBusy = false,
          preview = result.preview,
          executionResult = null,
        )
        is DesktopSkillRemovalResult.Failed -> current.copy(
          previewBusy = false,
          preview = null,
          executionResult = result,
          partialMutationLocked = false,
        )
        is DesktopSkillRemovalResult.Success -> current.copy(
          previewBusy = false,
          preview = null,
          executionResult = DesktopSkillRemovalResult.Failed(
            exceptionName = "IllegalPreviewResponse",
            exceptionMessage = "Gateway returned Success for preview mode.",
            rollbackComplete = true,
          ),
        )
      }
      currentState = createState()
      currentState
    }

  fun beginExecuteRemoval(): SkillRemovalRunRequest? = with(state) {
    val current = confirmDeletion ?: return null
    if (!current.deleteEnabled) {
      return null
    }
    if (busyOperation != null) {
      return null
    }
    val repoRoot = currentSession?.repoPath?.takeIf { it.isNotBlank() } ?: return null
    activeRemovalToken += 1
    confirmDeletion = current.copy(executeBusy = true, executionResult = null)
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.DELETE
    currentState = createState()
    SkillRemovalRunRequest(
      token = activeRemovalToken,
      payload = DesktopSkillRemovalRequest(target = current.target, repoRootAbsolutePath = repoRoot),
    )
  }

  suspend fun runExecuteRemoval(request: SkillRemovalRunRequest): DesktopSkillRemovalResult =
    skillRemoveGateway.execute(request.payload)

  fun finishExecuteRemoval(request: SkillRemovalRunRequest, result: DesktopSkillRemovalResult): SkillBillState =
    with(state) {
      capturePartialMutationPostMortem(request, result)
      if (request.token != activeRemovalToken) {
        if (busyOperation == SkillBillBusyOperation.DELETE) {
          busyOperation = null
        }
        currentState = createState()
        return currentState
      }
      val current = confirmDeletion ?: return currentState
      busyOperation = null
      confirmDeletion = when (result) {
        is DesktopSkillRemovalResult.Success -> current.copy(
          executeBusy = false,
          executionResult = result,
        )
        is DesktopSkillRemovalResult.Failed -> current.copy(
          executeBusy = false,
          executionResult = result,
          partialMutationLocked = !result.rollbackComplete,
        )
        is DesktopSkillRemovalResult.Preview -> current.copy(
          executeBusy = false,
          executionResult = DesktopSkillRemovalResult.Failed(
            exceptionName = "IllegalExecuteResponse",
            exceptionMessage = "Gateway returned Preview for execute mode.",
            rollbackComplete = true,
          ),
        )
      }
      currentState = createState()
      currentState
    }

  private fun capturePartialMutationPostMortem(request: SkillRemovalRunRequest, result: DesktopSkillRemovalResult) =
    with(state) {
      if (result !is DesktopSkillRemovalResult.Failed || result.rollbackComplete) return@with
      val target = request.payload.target
      val label = when (target) {
        is DesktopSkillRemovalTarget.HorizontalSkill -> target.skillName
        is DesktopSkillRemovalTarget.PlatformPack ->
          "platform pack '${target.platform}'"
        is DesktopSkillRemovalTarget.AddOn -> target.relativePath
        is DesktopSkillRemovalTarget.ExternalAddOn -> "${target.sourceRootAbsolutePath}/${target.fileName}"
      }
      partialMutationPostMortem = PartialMutationPostMortem(
        targetLabel = label,
        exceptionName = result.exceptionName,
        exceptionMessage = result.exceptionMessage,
      )
    }
}

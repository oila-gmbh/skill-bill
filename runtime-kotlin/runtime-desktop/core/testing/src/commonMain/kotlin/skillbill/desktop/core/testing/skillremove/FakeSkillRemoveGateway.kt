package skillbill.desktop.core.testing.skillremove

import skillbill.desktop.core.domain.model.DesktopSkillRemovalRequest
import skillbill.desktop.core.domain.model.DesktopSkillRemovalResult
import skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget
import skillbill.desktop.core.domain.service.RuntimeSkillRemoveGateway

/**
 * SKILL-46: in-memory fake of [RuntimeSkillRemoveGateway] for desktop ViewModel + dialog tests.
 *
 * Mirrors the per-kind scripting pattern of `FakeScaffoldGateway`: tests script per-scope
 * Preview/Success/Failed outcomes via [enqueuePreview] / [enqueueExecute] and read back recorded
 * calls via [previewCalls] / [executeCalls] to assert payload parity (AC2/AC9).
 */
class FakeSkillRemoveGateway : RuntimeSkillRemoveGateway {
  private val _previewCalls: MutableList<DesktopSkillRemovalRequest> = mutableListOf()
  private val _executeCalls: MutableList<DesktopSkillRemovalRequest> = mutableListOf()

  val previewCalls: List<DesktopSkillRemovalRequest>
    get() = _previewCalls.toList()

  val executeCalls: List<DesktopSkillRemovalRequest>
    get() = _executeCalls.toList()

  /** Per-target-kind FIFO queue of preview outcomes. */
  private val previewQueues: MutableMap<TargetKind, ArrayDeque<DesktopSkillRemovalResult>> = mutableMapOf()
  private val executeQueues: MutableMap<TargetKind, ArrayDeque<DesktopSkillRemovalResult>> = mutableMapOf()

  /** Fallback response when no per-kind script is queued. */
  var defaultPreviewResponse: DesktopSkillRemovalResult? = null
  var defaultExecuteResponse: DesktopSkillRemovalResult? = null

  fun enqueuePreview(target: DesktopSkillRemovalTarget, result: DesktopSkillRemovalResult) {
    previewQueues.getOrPut(target.toKind()) { ArrayDeque() }.addLast(result)
  }

  fun enqueueExecute(target: DesktopSkillRemovalTarget, result: DesktopSkillRemovalResult) {
    executeQueues.getOrPut(target.toKind()) { ArrayDeque() }.addLast(result)
  }

  fun clear() {
    _previewCalls.clear()
    _executeCalls.clear()
    previewQueues.clear()
    executeQueues.clear()
    defaultPreviewResponse = null
    defaultExecuteResponse = null
  }

  override suspend fun preview(request: DesktopSkillRemovalRequest): DesktopSkillRemovalResult {
    _previewCalls += request
    val queue = previewQueues[request.target.toKind()]
    return queue?.removeFirstOrNull()
      ?: defaultPreviewResponse
      ?: error("FakeSkillRemoveGateway has no preview response scripted for ${request.target}.")
  }

  override suspend fun execute(request: DesktopSkillRemovalRequest): DesktopSkillRemovalResult {
    _executeCalls += request
    val queue = executeQueues[request.target.toKind()]
    return queue?.removeFirstOrNull()
      ?: defaultExecuteResponse
      ?: error("FakeSkillRemoveGateway has no execute response scripted for ${request.target}.")
  }

  private enum class TargetKind { HORIZONTAL_SKILL, PLATFORM_PACK, ADDON }

  private fun DesktopSkillRemovalTarget.toKind(): TargetKind = when (this) {
    is DesktopSkillRemovalTarget.HorizontalSkill -> TargetKind.HORIZONTAL_SKILL
    is DesktopSkillRemovalTarget.PlatformPack -> TargetKind.PLATFORM_PACK
    is DesktopSkillRemovalTarget.AddOn -> TargetKind.ADDON
  }
}

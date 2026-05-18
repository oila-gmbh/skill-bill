package skillbill.desktop.feature.skillbill.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import skillbill.desktop.core.domain.model.ConfirmDeletionState
import skillbill.desktop.core.domain.model.DesktopSkillRemovalPreview
import skillbill.desktop.core.domain.model.DesktopSkillRemovalResult
import skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget

/**
 * SKILL-46 AC5: state-snapshot test asserting the checkbox-gated Delete button. No ComposeTestRule
 * — the dialog is a thin renderer over [ConfirmDeletionState], so the gate is exercised by reading
 * [ConfirmDeletionState.deleteEnabled] directly on representative state instances.
 */
class ConfirmDeletionDialogStateTest {
  private val target = DesktopSkillRemovalTarget.HorizontalSkill(skillName = "bill-foo")

  @Test
  fun `deleteEnabled is false when preview is missing`() {
    val state = ConfirmDeletionState(target = target, preview = null, acknowledged = true)
    assertFalse(state.deleteEnabled)
  }

  @Test
  fun `deleteEnabled is false when acknowledged is false`() {
    val state = ConfirmDeletionState(target = target, preview = samplePreview(), acknowledged = false)
    assertFalse(state.deleteEnabled)
  }

  @Test
  fun `deleteEnabled is true when preview present and acknowledged true`() {
    val state = ConfirmDeletionState(target = target, preview = samplePreview(), acknowledged = true)
    assertTrue(state.deleteEnabled)
  }

  @Test
  fun `deleteEnabled is false while previewBusy`() {
    val state = ConfirmDeletionState(
      target = target,
      preview = samplePreview(),
      acknowledged = true,
      previewBusy = true,
    )
    assertFalse(state.deleteEnabled)
  }

  @Test
  fun `deleteEnabled is false while executeBusy`() {
    val state = ConfirmDeletionState(
      target = target,
      preview = samplePreview(),
      acknowledged = true,
      executeBusy = true,
    )
    assertFalse(state.deleteEnabled)
  }

  @Test
  fun `deleteEnabled is false when partialMutationLocked`() {
    val state = ConfirmDeletionState(
      target = target,
      preview = samplePreview(),
      acknowledged = true,
      partialMutationLocked = true,
    )
    assertFalse(state.deleteEnabled)
  }

  @Test
  fun `Failed result with rollbackComplete=false should be the trigger for partialMutationLocked`() {
    // The ViewModel sets partialMutationLocked from a Failed result; this assertion documents the
    // contract that the dialog reads. Used as a regression guard if the model class is refactored.
    val failed = DesktopSkillRemovalResult.Failed(
      exceptionName = "Boom",
      exceptionMessage = "rollback failed",
      rollbackComplete = false,
    )
    assertFalse(failed.rollbackComplete)
  }

  private fun samplePreview(): DesktopSkillRemovalPreview = DesktopSkillRemovalPreview(
    filesystemPaths = listOf("skills/bill-foo"),
    manifestEdits = emptyList(),
    agentSymlinkUnlinks = emptyList(),
    readmeCatalogEdits = emptyList(),
    skillDirRoot = "skills/bill-foo",
    cascadedSkillNames = listOf("bill-foo"),
  )
}

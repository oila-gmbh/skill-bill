package skillbill.desktop.feature.skillbill.ui

import skillbill.desktop.core.domain.model.CommandPaletteAction
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.SkillBillAcceleratorLabels
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.feature.skillbill.state.buildCommandPaletteState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyboardAcceleratorsTest {

  @Test
  fun `repository Enter and NumPadEnter open only when globally enabled`() {
    assertEquals(
      KeyboardAcceleratorAction.OPEN_REPOSITORY_PATH,
      resolveRepositoryPathKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.ENTER),
        predicates = enabledPredicates(repoOpenEnabled = true),
      ),
    )
    assertEquals(
      KeyboardAcceleratorAction.OPEN_REPOSITORY_PATH,
      resolveRepositoryPathKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.NUMPAD_ENTER),
        predicates = enabledPredicates(repoOpenEnabled = true),
      ),
    )
    assertNull(
      resolveRepositoryPathKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.ENTER),
        predicates = enabledPredicates(repoOpenEnabled = false),
      ),
    )
    assertNull(
      resolveRepositoryPathKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.NUMPAD_ENTER),
        predicates = enabledPredicates(repoOpenEnabled = true).copy(busyOperationActive = true),
      ),
    )
  }

  @Test
  fun `save accelerator requires command S and save enabled`() {
    assertEquals(
      KeyboardAcceleratorAction.SAVE,
      resolveFrameKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.S, commandPressed = true),
        predicates = enabledPredicates(saveEnabled = true),
      ),
    )
    assertNull(
      resolveFrameKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.S, commandPressed = true),
        predicates = enabledPredicates(saveEnabled = false),
      ),
    )
  }

  @Test
  fun `refresh accelerator requires command R and refresh enabled`() {
    assertEquals(
      KeyboardAcceleratorAction.REFRESH,
      resolveFrameKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.R, commandPressed = true),
        predicates = enabledPredicates(refreshEnabled = true),
      ),
    )
    assertNull(
      resolveFrameKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.R, commandPressed = true),
        predicates = enabledPredicates(refreshEnabled = false),
      ),
    )
  }

  @Test
  fun `global busy state blocks accelerators except command palette`() {
    assertNull(
      resolveFrameKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.S, commandPressed = true),
        predicates = enabledPredicates(saveEnabled = true).copy(busyOperationActive = true),
      ),
    )
    assertEquals(
      KeyboardAcceleratorAction.OPEN_COMMAND_PALETTE,
      resolveFrameKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.K, commandPressed = true),
        predicates = enabledPredicates(saveEnabled = true).copy(busyOperationActive = true),
      ),
    )
    assertEquals(
      KeyboardAcceleratorAction.OPEN_COMMAND_PALETTE,
      resolveFrameKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.P, commandPressed = true),
        predicates = enabledPredicates(saveEnabled = true),
      ),
    )
    assertEquals(
      KeyboardAcceleratorAction.OPEN_COMMAND_PALETTE,
      resolveFrameKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.K, commandPressed = true, shiftPressed = true),
        predicates = enabledPredicates(saveEnabled = true),
      ),
      "Shift must not change the existing command palette shortcut predicate.",
    )
  }

  @Test
  fun `command palette command rows carry accelerator labels`() {
    val palette = buildCommandPaletteState(
      state = paletteState(),
      open = true,
      query = "",
      selectedResultIndex = 0,
    )
    assertEquals(SkillBillAcceleratorLabels.REFRESH, palette.command(CommandPaletteAction.REFRESH).acceleratorLabel)
    assertEquals(SkillBillAcceleratorLabels.SAVE, palette.command(CommandPaletteAction.SAVE).acceleratorLabel)
  }

  @Test
  fun `accelerator labels advertise command and control modifiers`() {
    assertEquals("Cmd/Ctrl S", SkillBillAcceleratorLabels.SAVE)
    assertEquals("Cmd/Ctrl R", SkillBillAcceleratorLabels.REFRESH)
    assertEquals("Cmd/Ctrl K", SkillBillAcceleratorLabels.COMMAND_PALETTE)
  }

  @Test
  fun `frame accelerator dispatch invokes only enabled callback fakes`() {
    val callbacks = FakeFrameAcceleratorCallbacks()
    assertTrue(
      dispatchFrameKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.S, commandPressed = true),
        predicates = enabledPredicates(saveEnabled = true),
        callbacks = callbacks.asCallbacks(),
      ),
    )
    assertEquals(listOf("save"), callbacks.calls)

    assertFalse(
      dispatchFrameKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.S, commandPressed = true),
        predicates = enabledPredicates(saveEnabled = false),
        callbacks = callbacks.asCallbacks(),
      ),
    )
    assertEquals(listOf("save"), callbacks.calls)

    assertTrue(
      dispatchFrameKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.R, commandPressed = true),
        predicates = enabledPredicates(refreshEnabled = true),
        callbacks = callbacks.asCallbacks(),
      ),
    )
    assertEquals(listOf("save", "refresh"), callbacks.calls)
  }

  @Test
  fun `repository accelerator dispatch uses callback fakes only when enabled`() {
    val callbackCalls = mutableListOf<String>()
    assertTrue(
      dispatchRepositoryPathKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.ENTER),
        predicates = enabledPredicates(repoOpenEnabled = true),
        onOpenRepositoryPath = { callbackCalls += "repo" },
      ),
    )
    assertFalse(
      dispatchRepositoryPathKeyboardAccelerator(
        event = KeyboardAcceleratorEvent(KeyboardAcceleratorKey.ENTER),
        predicates = enabledPredicates(repoOpenEnabled = true).copy(busyOperationActive = true),
        onOpenRepositoryPath = { callbackCalls += "repo" },
      ),
    )
    assertEquals(listOf("repo"), callbackCalls)
  }

  private fun enabledPredicates(
    saveEnabled: Boolean = false,
    refreshEnabled: Boolean = false,
    repoOpenEnabled: Boolean = false,
  ): SkillBillAcceleratorPredicates = SkillBillAcceleratorPredicates(
    busyOperationActive = false,
    saveEnabled = saveEnabled,
    refreshEnabled = refreshEnabled,
    repoOpenEnabled = repoOpenEnabled,
  )

  private fun paletteState(): SkillBillState = SkillBillState(
    selectedRepoPath = "/repo",
    repoPathText = "/repo",
    repoStatus = RepoLoadStatus(state = RepoLoadState.LOADED, message = "Loaded"),
    treeItems = emptyList(),
    selectedTreeItemId = null,
    busyOperation = null,
    editor = EditorPlaceholder(
      title = "content.md",
      detail = "",
      editable = true,
      content = "before",
      draftContent = "after",
      dirty = true,
    ),
  )

  private fun skillbill.desktop.core.domain.model.CommandPaletteState.command(action: CommandPaletteAction) =
    results.single { it.action == action }

  private class FakeFrameAcceleratorCallbacks {
    val calls = mutableListOf<String>()

    fun asCallbacks(): FrameKeyboardAcceleratorCallbacks = FrameKeyboardAcceleratorCallbacks(
      openCommandPalette = { calls += "palette" },
      save = { calls += "save" },
      refresh = { calls += "refresh" },
    )
  }
}
